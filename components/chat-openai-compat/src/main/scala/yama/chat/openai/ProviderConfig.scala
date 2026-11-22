package yama.chat.openai

/** A provider is data: base URL, extra headers, capability flags. The API key comes separately as a ciris `Secret`.
  *
  * Every provider that exposes `POST {baseUrl}/responses` works: OpenAI, OpenRouter, DeepSeek, Ollama.
  */
final case class ProviderConfig(
    name: String,
    baseUrl: String,
    headers: Map[String, String] = Map.empty,
    strictTools: Boolean = false
)
