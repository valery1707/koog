# Functional agents

Functional agents are lightweight AI agents that operate without building complex strategy graphs. Instead, the agent logic is implemented as a lambda function that handles user input, interacts with an LLM, optionally calls tools, and produces a final output. It can perform a single LLM call, process multiple LLM calls in sequence, or loop based on user input, as well as LLM and tool outputs.

Tip

- If you already have a simple [single-run agent](../single-run-agents/) as your first MVP, but run into task-specific limitations, use a functional agent to prototype custom logic. You can implement custom control flows in plain Kotlin while still using most Koog features, including history compression and automatic state management.
- For production-grade needs, refactor your functional agent into a [complex workflow agent](../complex-workflow-agents/) with strategy graphs. This provides persistence with controllable rollbacks for fault-tolerance and advanced OpenTelemetry tracing with nested graph events.

This page guides you through the steps necessary to create a minimal functional agent and extend it with tools.

## Prerequisites

Before you start, make sure that you have the following:

- A working Kotlin/JVM project with Gradle.
- Java 17+ installed.
- A valid API key from the LLM provider used to implement an AI agent. For a list of all available providers, refer to [Overview](../).
- (Optional) Ollama installed and running locally if you use this provider.

Tip

Use environment variables or a secure configuration management system to store your API keys. Avoid hardcoding API keys directly in your source code.

## Add dependencies

The `AIAgent` class is the main class for creating agents in Koog. Include the following dependency in your build configuration to use the class functionality:

```text
dependencies {
    implementation("ai.koog:koog-agents:VERSION")
}
```

For all available installation methods, see [Installation](../#installation).

## Create a minimal functional agent

To create a minimal functional agent, do the following:

1. Choose the input and output types that the agent handles and create a corresponding `AIAgent<Input, Output>` instance. In this guide, we use `AIAgent<String, String>`, which means the agent receives and returns `String`.
1. Provide the required parameters, including a system prompt, prompt executor, and LLM.
1. Define the agent logic with a lambda function wrapped into the `functionalStrategy {...}` DSL method.

Here is an example of a minimal functional agent that sends user text to a specified LLM and returns a single assistant message.

```kotlin
// Create an AIAgent instance and provide a system prompt, prompt executor, and LLM
val mathAgent = AIAgent<String, String>(
    systemPrompt = "You are a precise math assistant.",
    promptExecutor = simpleOllamaAIExecutor(),
    llmModel = OllamaModels.Meta.LLAMA_3_2,
    strategy = functionalStrategy { input -> // Define the agent logic
        // Make one LLM call
        val response = requestLLM(input)
        // Extract and return the assistant message content from the response
        response.asAssistantMessage().content
    }
)

// Run the agent with a user input and print the result
val result = mathAgent.run("What is 12 × 9?")
println(result)
```

The agent can produce the following output:

```text
The answer to 12 × 9 is 108.
```

This agent makes a single LLM call and returns the assistant message content. You can extend the agent logic to handle multiple sequential LLM calls. For example:

```kotlin
// Create an AIAgent instance and provide a system prompt, prompt executor, and LLM
val mathAgent = AIAgent<String, String>(
    systemPrompt = "You are a precise math assistant.",
    promptExecutor = simpleOllamaAIExecutor(),
    llmModel = OllamaModels.Meta.LLAMA_3_2,
    strategy = functionalStrategy { input -> // Define the agent logic
        // The first LLM call to produce an initial draft based on the user input
        val draft = requestLLM("Draft: $input").asAssistantMessage().content
        // The second LLM call to improve the draft by prompting the LLM again with the draft content
        val improved = requestLLM("Improve and clarify: $draft").asAssistantMessage().content
        // The final LLM call to format the improved text and return the final formatted result
        requestLLM("Format the result as bold: $improved").asAssistantMessage().content
    }
)

// Run the agent with a user input and print the result
val result = mathAgent.run("What is 12 × 9?")
println(result)
```

The agent can produce the following output:

```text
When multiplying 12 by 9, we can break it down as follows:

**12 (tens) × 9 = 108**

Alternatively, we can also use the distributive property to calculate this:

**(10 + 2) × 9**
= **10 × 9 + 2 × 9**
= **90 + 18**
= **108**
```

## Add tools

In many cases, a functional agent needs to complete specific tasks, such as reading and writing data or calling APIs. In Koog, you expose such capabilities as tools and let the LLM call them in the agent logic.

This chapter takes the minimal functional agent created above and demonstrates how to extend the agent logic using tools.

1. Create an annotation-based tool. For more details, see [Annotation-based tools](../annotation-based-tools/).

```kotlin
@LLMDescription("Simple multiplier")
class MathTools : ToolSet {
    @Tool
    @LLMDescription("Multiplies two numbers and returns the result")
    fun multiply(a: Int, b: Int): Int {
        val result = a * b
        return result
    }
}
```

To learn more about available tools, refer to the [Tool overview](../tools-overview/).

2. Register the tool to make it available to the agent.

```kotlin
val toolRegistry = ToolRegistry {
    tools(MathTools())
}
```

3. Pass the tool registry to the agent to enable the LLM to request and use the available tools.

1. Extend the agent logic to identify tool calls, execute the requested tools, send their results back to the LLM, and repeat the process until no tool calls remain.

Note

Use a loop only if the LLM continues to issue tool calls.

```kotlin
val mathWithTools = AIAgent<String, String>(
    systemPrompt = "You are a precise math assistant. When multiplication is needed, use the multiplication tool.",
    promptExecutor = simpleOllamaAIExecutor(),
    llmModel = OllamaModels.Meta.LLAMA_3_2,
    toolRegistry = toolRegistry,
    strategy = functionalStrategy { input -> // Define the agent logic extended with tool calls
        // Send the user input to the LLM
        var responses = requestLLMMultiple(input)

        // Only loop while the LLM requests tools
        while (responses.containsToolCalls()) {
            // Extract tool calls from the response
            val pendingCalls = extractToolCalls(responses)
            // Execute the tools and return the results
            val results = executeMultipleTools(pendingCalls)
            // Send the tool results back to the LLM. The LLM may call more tools or return a final output
            responses = sendMultipleToolResults(results)
        }

        // When no tool calls remain, extract and return the assistant message content from the response
        responses.single().asAssistantMessage().content
    }
)

// Run the agent with a user input and print the result
val reply = mathWithTools.run("Please multiply 12.5 and 4, then add 10 to the result.")
println(reply)
```

The agent can produce the following output:

```text
Here is the step-by-step solution:

1. Multiply 12.5 and 4:
   12.5 × 4 = 50

2. Add 10 to the result:
   50 + 10 = 60
```

## What's next

- Learn how to return structured data using the [Structured output API](../structured-output/).
- Experiment with adding more [tools](../tools-overview/) to the agent.
- Improve observability with the [EventHandler](../agent-events/) feature.
- Learn how to handle long-running conversations with [History compression](../history-compression/).
