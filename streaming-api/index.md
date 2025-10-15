# Streaming API

## Introduction

Koog’s **Streaming API** lets you consume **LLM output incrementally** as a `Flow<StreamFrame>`. Instead of waiting for a full response, your code can:

- render assistant text as it arrives,
- detect **tool calls** live and act on them,
- know when a stream **ends** and why.

The stream carries **typed frames**:

- `StreamFrame.Append(text: String)` — incremental assistant text
- `StreamFrame.ToolCall(id: String?, name: String, content: String)` — tool invocation (combined safely)
- `StreamFrame.End(finishReason: String?)` — end-of-stream marker

Helpers are provided to extract plain text, convert frames to `Message.Response` objects, and safely **combine chunked tool calls**.

______________________________________________________________________

## Streaming API overview

With streaming you can:

- Process data as it arrives (improves UI responsiveness)
- Parse structured info on the fly (Markdown/JSON/etc.)
- Emit objects as they complete
- Trigger tools in real time

You can operate either on the **frames** themselves or on **plain text** derived from frames.

______________________________________________________________________

## Usage

### Working with frames directly

This is the most general approach: react to each frame kind.

```kotlin
llm.writeSession {
    updatePrompt { user("Tell me a joke, then call a tool with JSON args.") }

    val stream = requestLLMStreaming() // Flow<StreamFrame>

    stream.collect { frame ->
        when (frame) {
            is StreamFrame.Append -> print(frame.text)
            is StreamFrame.ToolCall -> {
                println("\n🔧 Tool call: ${frame.name} args=${frame.content}")
                // Optionally parse lazily:
                // val json = frame.contentJson
            }
            is StreamFrame.End -> println("\n[END] reason=${frame.finishReason}")
        }
    }
}
```

It is important to note that you can parse the output by working directly with a raw string stream. This approach gives you more flexibility and control over the parsing process.

Here is a raw string stream with the Markdown definition of the output structure:

```kotlin
fun markdownBookDefinition(): MarkdownStructuredDataDefinition {
    return MarkdownStructuredDataDefinition("name", schema = { /*...*/ })
}

val mdDefinition = markdownBookDefinition()

llm.writeSession {
    val stream = requestLLMStreaming(mdDefinition)
    // Access the raw string chunks directly
    stream.collect { chunk ->
        // Process each chunk of text as it arrives
        println("Received chunk: $chunk") // The chunks together will be structured as a text following the mdDefinition schema
    }
}
```

### Working with a raw text stream (derived)

If you have existing streaming parsers that expect `Flow<String>`, derive text chunks via `filterTextOnly()` or collect them with `collectText()`.

```kotlin
llm.writeSession {
    val frames = requestLLMStreaming()

    // Stream text chunks as they come:
    frames.filterTextOnly().collect { chunk -> print(chunk) }

    // Or, gather all text into one String after End:
    val fullText = frames.collectText()
    println("\n---\n$fullText")
}
```

### Listening to stream events in event handlers

You can listen to stream events in [agent event handlers](../agent-event-handlers/).

```kotlin
handleEvents {
    onToolCallStarting { context ->
        println("\n🔧 Using ${context.tool.name} with ${context.toolArgs}... ")
    }
    onLLMStreamingFrameReceived { context ->
        (context.streamFrame as? StreamFrame.Append)?.let { frame ->
            print(frame.text)
        }
    }
    onLLMStreamingFailed { context -> 
        println("❌ Error: ${context.error}")
    }
    onLLMStreamingCompleted {
        println("🏁 Done")
    }
}
```

### Converting frames to `Message.Response`

You can transform a collected list of frames to standard message objects:

- `toAssistantMessageOrNull()`
- `toToolCallMessages()`
- `toMessageResponses()`

______________________________________________________________________

## Examples

### Structured data while streaming (Markdown example)

Although it is possible to work with a raw string stream, it is often more convenient to work with [structured data](../structured-output/).

The structured data approach includes the following key components:

1. **MarkdownStructuredDataDefinition**: a class to help you define the schema and examples for structured data in Markdown format.
1. **markdownStreamingParser**: a function to create a parser that processes a stream of Markdown chunks and emits events.

The sections below provide step-by-step instructions and code samples related to processing a stream of structured data.

#### 1. Define your data structure

First, define a data class to represent your structured data:

```kotlin
@Serializable
data class Book(
    val title: String,
    val author: String,
    val description: String
)
```

#### 2. Define the Markdown structure

Create a definition that specifies how your data should be structured in Markdown with the `MarkdownStructuredDataDefinition` class:

```kotlin
fun markdownBookDefinition(): MarkdownStructuredDataDefinition {
    return MarkdownStructuredDataDefinition("bookList", schema = {
        markdown {
            header(1, "title")
            bulleted {
                item("author")
                item("description")
            }
        }
    }, examples = {
        markdown {
            header(1, "The Great Gatsby")
            bulleted {
                item("F. Scott Fitzgerald")
                item("A novel set in the Jazz Age that tells the story of Jay Gatsby's unrequited love for Daisy Buchanan.")
            }
        }
    })
}
```

#### 3. Create a parser for your data structure

The `markdownStreamingParser` provides several handlers for different Markdown elements:

```kotlin
markdownStreamingParser {
    // Handle level 1 headings (level ranges from 1 to 6)
    onHeader(1) { headerText -> }
    // Handle bullet points
    onBullet { bulletText -> }
    // Handle code blocks
    onCodeBlock { codeBlockContent -> }
    // Handle lines matching a regex pattern
    onLineMatching(Regex("pattern")) { line -> }
    // Handle the end of the stream
    onFinishStream { remainingText -> }
}
```

Using the defined handlers, you can implement a function that parses the Markdown stream and emits your data objects with the `markdownStreamingParser` function.

```kotlin
fun parseMarkdownStreamToBooks(markdownStream: Flow<StreamFrame>): Flow<Book> {
   return flow {
      markdownStreamingParser {
         var currentBookTitle = ""
         val bulletPoints = mutableListOf<String>()

         // Handle the event of receiving the Markdown header in the response stream
         onHeader(1) { headerText ->
            // If there was a previous book, emit it
            if (currentBookTitle.isNotEmpty() && bulletPoints.isNotEmpty()) {
               val author = bulletPoints.getOrNull(0) ?: ""
               val description = bulletPoints.getOrNull(1) ?: ""
               emit(Book(currentBookTitle, author, description))
            }

            currentBookTitle = headerText
            bulletPoints.clear()
         }

         // Handle the event of receiving the Markdown bullets list in the response stream
         onBullet { bulletText ->
            bulletPoints.add(bulletText)
         }

         // Handle the end of the response stream
         onFinishStream {
            // Emit the last book, if present
            if (currentBookTitle.isNotEmpty() && bulletPoints.isNotEmpty()) {
               val author = bulletPoints.getOrNull(0) ?: ""
               val description = bulletPoints.getOrNull(1) ?: ""
               emit(Book(currentBookTitle, author, description))
            }
         }
      }.parseStream(markdownStream.filterTextOnly())
   }
}
```

#### 4. Use the parser in your agent strategy

```kotlin
val agentStrategy = strategy<String, List<Book>>("library-assistant") {
   // Describe the node containing the output stream parsing
   val getMdOutput by node<String, List<Book>> { booksDescription ->
      val books = mutableListOf<Book>()
      val mdDefinition = markdownBookDefinition()

      llm.writeSession {
         updatePrompt { user(booksDescription) }
         // Initiate the response stream in the form of the definition `mdDefinition`
         val markdownStream = requestLLMStreaming(mdDefinition)
         // Call the parser with the result of the response stream and perform actions with the result
         parseMarkdownStreamToBooks(markdownStream).collect { book ->
            books.add(book)
            println("Parsed Book: ${book.title} by ${book.author}")
         }
      }

      books
   }
   // Describe the agent's graph making sure the node is accessible
   edge(nodeStart forwardTo getMdOutput)
   edge(getMdOutput forwardTo nodeFinish)
}
```

### Advanced usage: Streaming with tools

You can also use the Streaming API with tools to process data as it arrives. The following sections provide a brief step-by-step guide on how to define a tool and use it with streaming data.

### 1. Define a tool for your data structure

```kotlin
@Serializable
data class Book(
   val title: String,
   val author: String,
   val description: String
)

class BookTool(): SimpleTool<Book>() {

    companion object { const val NAME = "book" }

    override suspend fun doExecute(args: Book): String {
        println("${args.title} by ${args.author}:\n ${args.description}")
        return "Done"
    }

    override val argsSerializer: KSerializer<Book>
        get() = Book.serializer()

    override val name: String = NAME
    override val description: String = "A tool to parse book information from Markdown"
}
```

### 2. Use the tool with streaming data

```kotlin
val agentStrategy = strategy<String, Unit>("library-assistant") {
   val getMdOutput by node<String, Unit> { input ->
      val mdDefinition = markdownBookDefinition()

      llm.writeSession {
         updatePrompt { user(input) }
         val markdownStream = requestLLMStreaming(mdDefinition)

         parseMarkdownStreamToBooks(markdownStream).collect { book ->
            callToolRaw(BookTool.NAME, book)
            /* Other possible options:
                callTool(BookTool::class, book)
                callTool<BookTool>(book)
                findTool(BookTool::class).execute(book)
            */
         }

         // We can make parallel tool calls
         parseMarkdownStreamToBooks(markdownStream).toParallelToolCallsRaw(toolClass=BookTool::class).collect {
            println("Tool call result: $it")
         }
      }
   }

   edge(nodeStart forwardTo getMdOutput)
   edge(getMdOutput forwardTo nodeFinish)
 }
```

### 3. Register the tool in your agent configuration

```kotlin
val toolRegistry = ToolRegistry {
   tool(BookTool())
}

val runner = AIAgent(
   promptExecutor = simpleOpenAIExecutor(token),
   toolRegistry = toolRegistry,
   strategy = agentStrategy,
   agentConfig = agentConfig
)
```

## Best practices

1. **Define clear structures**: create clear and unambiguous markdown structures for your data.
1. **Provide good examples**: include comprehensive examples in your `MarkdownStructuredDataDefinition` to guide the LLM.
1. **Handle incomplete data**: always check for null or empty values when parsing data from the stream.
1. **Clean up resources**: use the `onFinishStream` handler to clean up resources and process any remaining data.
1. **Handle errors**: implement proper error handling for malformed Markdown or unexpected data.
1. **Testing**: test your parser with various input scenarios, including partial chunks and malformed input.
1. **Parallel processing**: for independent data items, consider using parallel tool calls for better performance.
