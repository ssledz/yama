package yama.conversation

/** How a query ended: the model answered, or the loop hit its round budget. */
enum Answer:
  case Final(reply: String, rounds: Int)
  case GaveUp(maxRounds: Int)

  def text: String = this match
    case Final(reply, _)   => reply
    case GaveUp(maxRounds) => s"Stopped after $maxRounds rounds without a final answer."
