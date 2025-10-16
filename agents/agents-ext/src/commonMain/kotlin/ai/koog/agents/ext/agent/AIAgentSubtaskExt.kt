package ai.koog.agents.ext.agent

import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.context.DetachedPromptExecutorAPI
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.executeTool
import ai.koog.agents.core.environment.toSafeResult
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams

/**
 * Executes a subtask with validation and verification of the results.
 * The method defines a subtask for the AI agent using the provided input
 * and additional parameters and ensures that the output is evaluated
 * based on its correctness and feedback.
 *
 * @param Input The type of the input provided to the subtask.
 * @param input The input data for the subtask, which will be used to
 * create and execute the task.
 * @param tools An optional list of tools that can be utilized during
 * the execution of the subtask.
 * @param llmModel An optional parameter specifying the LLM model to be used for the subtask.
 * @param llmParams Optional configuration parameters for the LLM, such as temperature
 * and token limits.
 * @param parallelTools A flag indicating whether tools should be executed
 * in parallel. Defaults to false.
 * @param assistantResponseRepeatMax An optional parameter specifying the maximum number of
 * retries for obtaining valid responses from the assistant.
 * @param defineTask A suspend function that defines the subtask as a string
 * based on the provided input.
 * @return A [CriticResult] object containing the verification status, feedback,
 * and the original input for the subtask.
 */
@OptIn(InternalAgentToolsApi::class, InternalAgentsApi::class)
public suspend inline fun <reified Input> AIAgentFunctionalContext.subtaskWithVerification(
    input: Input,
    tools: List<Tool<*, *>>? = null,
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    parallelTools: Boolean = false,
    assistantResponseRepeatMax: Int? = null,
    defineTask: suspend AIAgentFunctionalContext.(input: Input) -> String
): CriticResult<Input> {
    val result = subtask<Input, CriticResultFromLLM>(
        input,
        tools,
        llmModel,
        llmParams,
        parallelTools,
        assistantResponseRepeatMax,
        defineTask
    )

    return CriticResult(
        successful = result.isCorrect,
        feedback = result.feedback,
        input = input
    )
}

/**
 * Executes a subtask within the larger context of an AI agent's functional operation. This method allows you to define a specific
 * task to be performed, utilizing the given input, tools, and optional configuration parameters.
 *
 * @param Input The type of input provided to the subtask.
 * @param Output The type of the output expected from the subtask.
 * @param input The input data required for the subtask execution.
 * @param tools A list of tools available for use within the subtask.
 * @param llmModel The optional large language model to be used during the subtask, if different from the default one.
 * @param llmParams The configuration parameters for the large language model, such as temperature, etc.
 * @param parallelTools A flag indicating whether tools should be executed in parallel. Defaults to false (sequential execution).
 * @param assistantResponseRepeatMax The maximum number of times the assistant response can repeat. Useful to control redundant outputs.
 * @param defineTask A suspendable lambda defining the actual task logic, which takes the provided input and produces a task description.
 * @return The result of the subtask execution, as an instance of type Output.
 */
@OptIn(InternalAgentToolsApi::class)
public suspend inline fun <reified Input, reified Output> AIAgentFunctionalContext.subtask(
    input: Input,
    tools: List<Tool<*, *>>? = null,
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    parallelTools: Boolean = false,
    assistantResponseRepeatMax: Int? = null,
    defineTask: suspend AIAgentFunctionalContext.(input: Input) -> String
): Output {
    val finishTool = identityTool<Output>()

    return subtask(input, tools, finishTool, llmModel, llmParams, parallelTools, assistantResponseRepeatMax, defineTask)
}

/**
 * Executes a subtask within the context of an AI agent's functional execution loop. The subtask involves
 * defining a task based on the given input, utilizing the provided tools, and finalizing the task through
 * the specified finish tool. It also supports optional customization of the LLModel and LLModel parameters.
 *
 * @param Input The type of the input data for the subtask.
 * @param Output The type of the expected output from the subtask.
 * @param OutputTransformed The type of the transformed output after processing by the finish tool.
 * @param input The input data to be used for defining and executing the subtask.
 * @param tools A list of tools available for use during the subtask execution.
 * @param finishTool The tool to be used for finalizing the subtask, transforming the output to the desired format.
 * @param llmModel The optional large language model to be used for the subtask execution. Defaults to the current model in use.
 * @param llmParams The optional parameters for configuring the large language model. Defaults to the current configuration.
 * @param defineTask A lambda function to define the task based on the given input within the AI agent's graph context.
 */
@OptIn(InternalAgentToolsApi::class, DetachedPromptExecutorAPI::class, InternalAgentsApi::class)
public suspend inline fun <reified Input, reified Output, reified OutputTransformed> AIAgentFunctionalContext.subtask(
    input: Input,
    tools: List<Tool<*, *>>? = null,
    finishTool: Tool<Output, OutputTransformed>,
    llmModel: LLModel? = null,
    llmParams: LLMParams? = null,
    parallelTools: Boolean = false,
    assistantResponseRepeatMax: Int? = null,
    defineTask: suspend AIAgentFunctionalContext.(input: Input) -> String
): OutputTransformed {
    var fedbacksCount = 0
    val maxAssistantResponses = assistantResponseRepeatMax ?: SubgraphWithTaskUtils.ASSISTANT_RESPONSE_REPEAT_MAX

    val toolsSubset = tools?.map { it.descriptor } ?: llm.readSession { this.tools.toList() }

    val originalTools = llm.readSession { this.tools.toList() }

    // setup:
    llm.writeSession {
        if (finishTool.descriptor !in toolsSubset) {
            this.tools = toolsSubset + finishTool.descriptor
        }

        setToolChoiceRequired()
    }

    val task = defineTask(input)

    var responses = requestLLMMultiple(task)
    while (true) {
        when {
            responses.containsToolCalls() -> {
                val toolCalls = extractToolCalls(responses).filterNot { it.tool == finishTool.descriptor.name }
                val toolResults = executeMultipleToolsHacked(toolCalls, finishTool, parallelTools)
                responses = sendMultipleToolResults(toolResults)

                toolResults.firstOrNull { it.tool == finishTool.descriptor.name }
                    ?.let { finishResult ->
                        // Restore original tools
                        llm.writeSession {
                            this.tools = originalTools
                        }

                        return finishResult.toSafeResult<OutputTransformed>().asSuccessful().result
                    }
            }

            else -> {
                if (fedbacksCount++ > maxAssistantResponses) {
                    error(
                        "Unable to finish subtask. Reason: the model '${llm.model.id}' does not support tool choice, " +
                                "and was not able to call `${finishTool.name}` tool after " +
                                "<$maxAssistantResponses> attempts."
                    )
                }

                responses = requestLLMMultiple(
                    message = markdown {
                        h1("DO NOT CHAT WITH ME DIRECTLY! CALL TOOLS, INSTEAD.")
                        h2("IF YOU HAVE FINISHED, CALL `${finishTool.name}` TOOL!")
                    }
                )
            }
        }
    }
}

/**
 * Internal method. Not for public use.
 *
 * @param Output The type of the output produced by the finish tool.
 * @param OutputTransformed The type of the transformed output produced by the finish tool.
 * @param toolCalls A list of tool invocation requests, each containing the necessary details to execute a tool.
 * @param finishTool A specific tool to be treated as a finish tool, processed individually from other tools.
 * @param parallelTools A flag indicating whether other tools (non-finish tools) should be executed in parallel.
 * Defaults to `false`, meaning sequential execution.
 * @return A list of results from executing the tools. The results from the finish tool will appear first,
 * followed by results from other tools.
 */
@OptIn(InternalAgentToolsApi::class)
@InternalAgentsApi
public suspend inline fun <reified Output, reified OutputTransformed> AIAgentFunctionalContext.executeMultipleToolsHacked(
    toolCalls: List<Message.Tool.Call>,
    finishTool: Tool<Output, OutputTransformed>,
    parallelTools: Boolean = false
): List<ReceivedToolResult> {
    val finishTools = toolCalls.filter { it.tool == finishTool.descriptor.name }
    val normalTools = toolCalls.filterNot { it.tool == finishTool.descriptor.name }

    val finishToolResults = finishTools.map { toolCall ->
        executeFinishTool(toolCall, finishTool)
    }

    val normalToolResults = if (parallelTools) {
        environment.executeTools(normalTools)
    } else {
        normalTools.map { environment.executeTool(it) }
    }

    return finishToolResults + normalToolResults
}
