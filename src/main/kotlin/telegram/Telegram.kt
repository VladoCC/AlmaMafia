package org.example.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.handlers.CallbackQueryHandlerEnvironment
import com.github.kotlintelegrambot.dispatcher.handlers.TextHandlerEnvironment
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.HideKeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import kotlinx.coroutines.flow.*
import org.bson.types.ObjectId
import org.example.deleteMsgCommand
import org.example.logger
import org.example.telegram.ParametrizedProcessor.HandlerContext
import org.slf4j.Logger

data class Command(val name: String, val callback: String, val paramCount: Int) {
    infix fun named(name: String) = copy(name = name)
}

class Store(query: String) : ParameterStore {
    private val params: List<String> = Query.params(query)
    private fun get(index: Int): String = params[index]
    private fun <T> value(index: Int, cast: String.() -> T): T = get(index).cast()

    override fun int(index: Int) = value(index) { toInt() }
    override fun long(index: Int) = value(index) { toLong() }
    override fun id(index: Int) = value(index) { ObjectId(this) }
    override fun str(index: Int) = value(index) { this }

    override fun isInt(index: Int): Boolean {
        return try {
            get(index).toInt()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    override fun isLong(index: Int): Boolean {
        return try {
            get(index).toLong()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    override fun isId(index: Int): Boolean {
        return get(index).length == 24
    }
}

interface ParameterStore {
    fun int(index: Int): Int
    fun long(index: Int): Long
    fun id(index: Int): ObjectId
    fun str(index: Int): String

    fun isInt(index: Int): Boolean
    fun isLong(index: Int): Boolean
    fun isId(index: Int): Boolean
}

interface Processor {
    fun match(query: String): Boolean
    fun process(query: String, context: CallContext)
}

class ParametrizedProcessor(private val callbackKey: String, private val handlerFun: HandlerContext.() -> Unit) :
    Processor {
    private val callbackPrefix = "$callbackKey:"

    override fun match(query: String) = query == callbackKey || query.startsWith(callbackPrefix)

    override fun process(query: String, context: CallContext) {
        HandlerContext(
            context,
            Store(query)
        ).handlerFun()
    }

    class HandlerContext(private val context: CallContext, private val store: ParameterStore) :
        CallContext by context, ParameterStore by store
}

class BasicProcessor(private val callbackKey: String, private val handlerFun: HandlerContext.() -> Unit) : Processor {

    override fun match(query: String) = query == callbackKey

    override fun process(query: String, context: CallContext) {
        HandlerContext(context).handlerFun()
    }

    class HandlerContext(private val context: CallContext) : CallContext by context
}

class AnyProcessor(private val handlerFun: CallContext.() -> Unit) : Processor {
    override fun match(query: String) = true
    override fun process(query: String, context: CallContext) {
        context.handlerFun()
    }
}

class ErrorProcessor(private val handlerFun: CallContext.() -> Unit) {
    fun process(query: String, context: CallContext) {
        context.handlerFun()
    }
}

sealed class CommandBlock {
    abstract suspend fun process(query: String, bot: Bot, context: CallContext): Boolean

    class Return<T>(val value: T?, val result: Boolean)
}

class SingleCommandBlock(private val processor: Processor) : CommandBlock() {
    override suspend fun process(query: String, bot: Bot, context: CallContext): Boolean {
        return if (processor.match(query)) {
            processor.process(query, context)
            true
        } else {
            false
        }
    }
}

class ContainerBlock<C : CallContext, P> private constructor(
    private val handlerFun: suspend C.(P) -> Unit,
    private val producer: (String, CallContext, ErrorProcessor?, FlowCollector<CommandBlock>) -> C,
    private val condition: ConditionContext.(String) -> Return<P>,
    private val errorProcessor: ErrorProcessor?
) : CommandBlock() {
    private val log: Logger = logger<ContainerBlock<C, P>>()

    companion object {
        fun <P> basic(
            definition: suspend BasicContext.(P) -> Unit,
            errorProcessor: ErrorProcessor?,
            condition: ConditionContext.(String) -> Return<P>
        ): ContainerBlock<BasicContext, P> {
            return ContainerBlock(
                definition,
                { query, context, errorProc, flow -> BasicContext(context, flow, errorProc) },
                condition,
                errorProcessor
            )
        }

        fun <P> parametrized(
            definition: suspend ParametrizedContext.(P) -> Unit,
            errorProcessor: ErrorProcessor?,
            condition: ConditionContext.(String) -> Return<P>
        ): ContainerBlock<ParametrizedContext, P> {
            return ContainerBlock(
                definition,
                { query, context, errorProc, flow -> ParametrizedContext(context, Store(query), flow, errorProc) },
                condition,
                errorProcessor
            )
        }
    }

    fun match(query: String, context: CallContext): Return<P> {
        return ConditionContext(context).condition(query)
    }

    override suspend fun process(query: String, bot: Bot, context: CallContext): Boolean {
        val ret = match(query, context)
        return if (ret.result) {
            val result = flow {
                try {
                    ret.value?.let {
                        try {
                            producer(query, context, errorProcessor, this).handlerFun(it)
                        } catch (e: Error) {
                            log.error("Error occurred during message handling, query: $query", e)
                        }
                    }
                } catch (e: Error) {
                    log.error("Error occurred during flow processing, query: $query", e)
                }
            }.transformWhile {
                if (it.process(query, bot, context)) {
                    emit(it)
                    false
                } else {
                    true
                }
            }.take(1).singleOrNull()
            if (result == null) {
                errorProcessor?.process(query, context)
            }
            true
        } else {
            false
        }
    }

    open class BasicContext(
        context: CallContext,
        collector: FlowCollector<CommandBlock>,
        errorProcessor: ErrorProcessor?
    ) : ErrorHandlingFlowContext(context, collector, errorProcessor) {
        suspend fun simple(vararg commands: Command, definition: BasicProcessor.HandlerContext.() -> Unit) {
            commands.forEach {
                emit(SingleCommandBlock(BasicProcessor(it.callback, definition)))
            }
        }

        suspend fun any(definition: CallContext.() -> Unit) {
            emit(SingleCommandBlock(AnyProcessor(definition)))
        }

        suspend fun <P> block(
            condition: ConditionContext.(String) -> Return<P>,
            definition: suspend BasicContext.(P) -> Unit,
        ) {
            emit(basic(definition, errorProcessor, condition))
        }

        suspend fun block(condition: Boolean, definition: suspend BasicContext.(Unit) -> Unit) {
            emit(basic(definition, errorProcessor) { allowIf { condition } })
        }
    }

    class ParametrizedContext(
        context: CallContext,
        private val store: ParameterStore,
        collector: FlowCollector<CommandBlock>,
        errorProcessor: ErrorProcessor?
    ) : ErrorHandlingFlowContext(context, collector, errorProcessor), ParameterStore by store {
        suspend fun parametrized(vararg commands: Command, definition: HandlerContext.() -> Unit) {
            commands.forEach {
                emit(SingleCommandBlock(ParametrizedProcessor(it.callback, definition)))
            }
        }

        suspend fun simple(vararg commands: Command, definition: BasicProcessor.HandlerContext.() -> Unit) {
            commands.forEach {
                emit(SingleCommandBlock(BasicProcessor(it.callback, definition)))
            }
        }

        suspend fun any(definition: CallContext.() -> Unit) {
            emit(SingleCommandBlock(AnyProcessor(definition)))
        }

        suspend fun <P> block(
            condition: ConditionContext.(String) -> Return<P>,
            definition: suspend ParametrizedContext.(P) -> Unit
        ) {
            emit(parametrized(definition, errorProcessor, condition))
        }

        suspend fun block(condition: Boolean, definition: suspend ParametrizedContext.(Unit) -> Unit) {
            emit(parametrized(definition, errorProcessor) { allowIf { condition } })
        }
    }

    open class ErrorHandlingFlowContext(
        context: CallContext,
        collector: FlowCollector<CommandBlock>,
        protected val errorProcessor: ErrorProcessor?
    ) : FlowContext(context, collector)

    open class FlowContext(context: CallContext, private val collector: FlowCollector<CommandBlock>) :
        CallContext by context, FlowCollector<CommandBlock> by collector

    open class ConditionContext(context: CallContext) : CallContext by context {
        fun <T> deny(): Return<T> = Return(null, false)
        fun <T> allow(returnFun: () -> T) = Return(returnFun(), true)
        fun allowIf(conditionFun: () -> Boolean) = Return(Unit, conditionFun())
        fun <T> notNull(returnFun: () -> T?): Return<T> = with(returnFun()) {
            this?.let { allow { this } } ?: deny()
        }
    }
}

class TextHandler(
    errorProcessor: CallContext.() -> Unit = { },
    definition: suspend ContainerBlock.BasicContext.(Unit) -> Unit
) : Handler<ContainerBlock.BasicContext>(ContainerBlock.basic(definition, ErrorProcessor(errorProcessor)) { allow {} })

class TextQuery(private val text: String, private val bot: Bot, private val context: CallContext) {
    suspend infix fun by(handler: TextHandler) {
        handler.handle(text, bot, context)
    }
}

fun TextHandlerEnvironment.context(query: String) = Call(
    query,
    message.chat.id,
    message.messageId,
    message.from?.username ?: "",
    bot
)

fun TextHandlerEnvironment.handle(text: String) =
    TextQuery(text, bot, context(text))


class QueryHandler(
    errorProcessor: ErrorProcessor? = null,
    definition: suspend ContainerBlock.ParametrizedContext.(Unit) -> Unit
) : Handler<ContainerBlock.ParametrizedContext>(ContainerBlock.parametrized(definition, errorProcessor) { allow {} })

sealed class Handler<C : CallContext>(private val block: ContainerBlock<C, Unit>) {
    suspend fun handle(query: String, bot: Bot, callContext: CallContext) {
        if (block.match(query, callContext).result) {
            block.process(query, bot, callContext)
        }
    }
}

class ParametrizedQuery(private val query: String, private val bot: Bot, private val context: CallContext) {
    suspend infix fun by(handler: QueryHandler) {
        handler.handle(query, bot, context)
    }
}

fun CallbackQueryHandlerEnvironment.context(query: String) = with(callbackQuery.message?.chat?.id ?: -1L) {
    Call(query, this, callbackQuery.message?.messageId, callbackQuery.from.username ?: "", bot)
}

fun CallbackQueryHandlerEnvironment.handle(query: String) =
    ParametrizedQuery(query, bot, context(query))


fun inlineKeyboard(definition: KeyboardContext.() -> Unit) =
    InlineKeyboardMarkup.create(
        mutableListOf<List<InlineKeyboardButton>>()
            .also { KeyboardContext(it).definition() }
    )

class KeyboardContext(private val list: MutableList<List<InlineKeyboardButton>>) {
    fun row(definition: RowContext.() -> Unit) {
        val rowContent = mutableListOf<InlineKeyboardButton>().also { RowContext(it).definition() }
        list.add(rowContent)
    }

    fun button(command: Command, vararg params: Any) = row { button(command, *params) }

    class RowContext(private val list: MutableList<InlineKeyboardButton>) {
        fun button(command: Command, vararg params: Any) {
            assert(params.size == command.paramCount) { "Unable to create button, expected ${command.paramCount} parameters, found ${params.size}" }
            list.add(
                InlineKeyboardButton.CallbackData(
                    command.name,
                    Query.create(command.callback, *params)
                )
            )
        }
    }
}

fun footerKeyboard(definition: FooterContext.() -> Unit) = with(
    mutableListOf<List<KeyboardButton>>()
        .also { FooterContext(it).definition() }
) {
    if (this.isNotEmpty()) {
        KeyboardReplyMarkup(
            keyboard = this,
            resizeKeyboard = true
        )
    } else {
        HideKeyboardReplyMarkup()
    }
}

class FooterContext(private val list: MutableList<List<KeyboardButton>>) {
    fun row(definition: RowContext.() -> Unit) {
        val rowContent = mutableListOf<KeyboardButton>().also { RowContext(it).definition() }
        list.add(rowContent)
    }

    fun button(command: Command) = row { button(command) }

    class RowContext(private val list: MutableList<KeyboardButton>) {
        fun button(command: Command) {
            list.add(KeyboardButton(command.name))
        }
    }
}

fun command(name: String) = Command(name, name, 0)
fun command(name: String, callback: String) = Command(name, callback, 0)
fun command(name: String, callback: String, paramCount: Int) = Command(name, callback, paramCount)

class Call(
    override val query: String,
    override val chatId: Long,
    override val messageId: Long?,
    override val username: String,
    override val bot: Bot
) : CallContext

sealed interface CallContext {
    val query: String
    val chatId: Long
    val messageId: Long?
    val username: String
    val bot: Bot

    fun sendClosable(text: String, definition: KeyboardContext.() -> Unit = {}): Long {
        val chat = ChatId.fromId(chatId)
        val res = bot.sendMessage(
            chat,
            text
        )
        if (res.isSuccess) {
            bot.editMessageReplyMarkup(
                chat,
                res.get().messageId,
                replyMarkup = inlineKeyboard {
                    definition()
                    button(deleteMsgCommand, res.get().messageId)
                }
            )
            return res.get().messageId
        }
        return -1L
    }
}

object Query {
    private const val prefixDelimeter = ':'
    private const val paramDelimeter = ","

    fun create(callback: String, vararg params: Any) = callback +
            if (params.isNotEmpty()) prefixDelimeter + params.joinToString(paramDelimeter) else ""

    fun params(query: String) = query.dropWhile { it != prefixDelimeter }.drop(1).split(paramDelimeter)
}