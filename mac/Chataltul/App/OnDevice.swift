import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Questions about the week, answered by the model that is already on this Mac (Apple Intelligence, macOS 26 or
/// newer), from the week's numbers alone. Nothing is sent anywhere. Without it, `Review.answer` answers a few
/// kinds of question from the same numbers.
enum OnDevice {
    static var available: Bool {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *) {
            if case .available = SystemLanguageModel.default.availability { return true }
        }
        #endif
        return false
    }

    static func answer(_ question: String, facts: String) async -> String? {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *), available {
            let session = LanguageModelSession(instructions: "You answer a question about one person's focus week in one or two short, plain sentences, using only these numbers. If the numbers don't say, say you don't know. Never invent a number.")
            if let r = try? await session.respond(to: facts + "\n\nQuestion: " + question) { return r.content }
        }
        #endif
        return nil
    }
}
