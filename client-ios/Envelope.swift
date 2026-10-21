import Foundation

// Конверт содержимого (то, что внутри E2E-шифра) — паритет Android Envelope.kt.

enum MsgType {
    static let text = "text"
    static let image = "image"
    static let file = "file"
    static let voice = "voice"
    static let videoNote = "video_note"
    static let status = "status"
    static let callOffer = "call_offer"
    static let callAnswer = "call_answer"
    static let callIce = "call_ice"
    static let callEnd = "call_end"
}

enum VeilEnvelope {
    static func text(_ body: String) -> Data {
        (["type": MsgType.text, "body": body] as [String: Any]).json()
    }

    static func media(type: String, name: String, bytes: Data) -> Data {
        (["type": type, "name": name,
          "data": bytes.base64EncodedString()] as [String: Any]).json()
    }

    static func groupMeta(gid: String, name: String, members: [String]) -> Data {
        (["type": "group_meta", "gid": gid, "name": name, "members": members] as [String: Any]).json()
    }

    static func groupMsg(gid: String, body: String) -> Data {
        (["type": MsgType.text, "gid": gid, "body": body] as [String: Any]).json()
    }

    static func status(_ body: String) -> Data {
        (["type": MsgType.status, "body": body,
          "exp": Int(Date().timeIntervalSince1970) + 86_400] as [String: Any]).json()
    }

    static func callOffer(_ sdp: String) -> Data {
        (["type": MsgType.callOffer, "sdp": sdp] as [String: Any]).json()
    }
    static func callAnswer(_ sdp: String) -> Data {
        (["type": MsgType.callAnswer, "sdp": sdp] as [String: Any]).json()
    }
    static func callIce(_ cand: String, _ mid: String) -> Data {
        (["type": MsgType.callIce, "cand": cand, "mid": mid] as [String: Any]).json()
    }
    static func callEnd(_ reason: String) -> Data {
        (["type": MsgType.callEnd, "reason": reason] as [String: Any]).json()
    }
}

enum VeilGroups {
    static func newId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(12).description
    }
    static func save(_ g: GroupInfo) {
        var all = Self.all
        all[g.id] = g
        let d = (try? JSONEncoder().encode(all)) ?? Data()
        UserDefaults.standard.set(d, forKey: "veil_groups")
    }
    static var all: [String: GroupInfo] {
        (UserDefaults.standard.data(forKey: "veil_groups"))
            .flatMap { try? JSONDecoder().decode([String: GroupInfo].self, from: $0) } ?? [:]
    }
}

struct GroupInfo: Codable, Identifiable {
    var id: String
    var name: String
    var members: [String]
}

extension Dictionary where Key == String, Value == Any {
    func json() -> Data {
        (try? JSONSerialization.data(withJSONObject: self)) ?? Data()
    }
}
