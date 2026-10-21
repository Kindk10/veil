// VeilApp.swift — точка входа iOS-клиента VEIL.
// Онбординг: создание аккаунта (ключи + регистрация на реле) → PIN → чаты.

import SwiftUI
import PhotosUI

@main
struct VeilApp: App {
    var body: some Scene {
        WindowGroup { RootView().preferredColorScheme(.dark) }
    }
}

let VeilGreen = Color(red: 0.29, green: 0.89, blue: 0.55)
let VeilBg = Color(red: 0.043, green: 0.059, blue: 0.051)
let VeilSurface = Color(red: 0.071, green: 0.102, blue: 0.086)

struct RootView: View {
    @State private var screen = "start"
    @State private var identity: VeilIdentity?
    @State private var api: VeilApi?
    @State private var pin = ""

    var body: some View {
        Group {
            if screen == "start" {
                if FileManager.default.fileExists(atPath: vaultPath) {
                    UnlockView(onUnlock: { pin2 in
                        pin = pin2
                        if SecureStorage.shared.open(pin: pin2) != nil { screen = "chats" }
                    })
                } else {
                    OnboardingView(onDone: { id, p in
                        identity = id; pin = p
                        api = VeilApi(identity: id)
                        screen = "chats"
                    })
                }
            } else if screen == "chats" {
                ChatsView(api: api ?? VeilApi(identity: identity ?? VeilIdentity()))
            }
        }
        .background(VeilBg)
    }
    var vaultPath: String {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("veil_vault.bin").path
    }
}

struct OnboardingView: View {
    var onDone: (VeilIdentity, String) -> Void
    @State private var pin = ""
    @State private var confirm = ""
    @State private var username = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Text("VEIL").font(.largeTitle.bold()).foregroundColor(VeilGreen)
            Text("Анонимный шифрованный мессенджер").foregroundColor(.gray)
            Spacer().frame(height: 20)
            SecureField("PIN (4 цифры)", text: $pin).keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder).padding(.horizontal, 40)
            SecureField("Повторите PIN", text: $confirm).keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder).padding(.horizontal, 40)
            TextField("Юзернейм", text: $username)
                .autocapitalization(.none).disableAutocorrection(true)
                .textFieldStyle(.roundedBorder).padding(.horizontal, 40)
            if let error { Text(error).foregroundColor(.red).font(.footnote) }
            Button(busy ? "Генерация ключей…" : "Создать аккаунт") {
                guard pin.count == 4, pin == confirm else { error = "PIN — 4 цифры, дважды"; return }
                busy = true
                let id = VeilIdentity()
                if SecureStorage.shared.create(pin: pin) {
                    let a = VeilApi(identity: id)
                    Task {
                        _ = await a.register()
                        if !username.isEmpty { _ = await a.setUsername(username.lowercased()) }
                        busy = false
                        onDone(id, pin)
                    }
                } else { busy = false; error = "Ошибка хранилища" }
            }
            .buttonStyle(.borderedProminent).tint(VeilGreen)
            Spacer()
            Text("PIN запоминается только вами. 5 неверных попыток — стирание безвозвратно.")
                .font(.caption2).foregroundColor(.gray).padding(.horizontal, 30)
        }
        .background(VeilBg)
    }
}

struct UnlockView: View {
    var onUnlock: (String) -> Void
    @State private var pin = ""
    @State private var error = false

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("VEIL").font(.largeTitle.bold()).foregroundColor(VeilGreen)
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder).padding(.horizontal, 40)
            if error { Text("Неверный PIN").foregroundColor(.red).font(.footnote) }
            Button("Разблокировать") {
                if SecureStorage.shared.open(pin: pin) != nil { onUnlock(pin) } else { error = true }
            }
            .buttonStyle(.borderedProminent).tint(VeilGreen)
            Spacer()
        }
        .background(VeilBg)
    }
}

struct ChatsView: View {
    var api: VeilApi
    @State private var query = ""
    @State private var peerHash: String?
    @State private var messages: [SecureStorage.Message] = []
    @State private var input = ""
    @State private var showPhotoPicker = false
    @State private var pickedItem: PhotosPickerItem?
    @State private var groupSheet = false
    @State private var groupName = ""
    @State private var groupMembers: [String] = []
    @State private var newMember = ""
    @State private var openGroup: GroupInfo?
    @State private var callPeer: String?
    @State private var callOfferSdp: String?
    @State private var incomingCall: (peer: String, sdp: String)?

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                if let g = openGroup {
                    groupChatView(g)
                } else if let hash = peerHash {
                    // Экран чата
                    HStack {
                        Button("←") { peerHash = nil }.foregroundColor(VeilGreen)
                        Text(query).font(.headline).foregroundColor(.white)
                        Spacer()
                        Button("📞") { callPeer = hash; callOfferSdp = nil }.foregroundColor(VeilGreen)
                        Text("E2E").font(.caption).foregroundColor(VeilGreen)
                    }.padding()
                    ScrollView {
                        ForEach(messages.indices, id: \.self) { i in
                            let m = messages[i]
                            HStack {
                                if m.dir == "out" { Spacer() }
                                Text(m.text).padding(10)
                                    .background(m.dir == "out" ? VeilGreen : VeilSurface)
                                    .foregroundColor(m.dir == "out" ? .black : .white)
                                    .cornerRadius(14)
                                if m.dir != "out" { Spacer() }
                            }.padding(.horizontal, 10).padding(.vertical, 2)
                        }
                    }
                    HStack {
                        TextField("Сообщение…", text: $input)
                            .textFieldStyle(.roundedBorder)
                        PhotosPicker(selection: $pickedItem, matching: .images) {
                            Image(systemName: "photo").foregroundColor(VeilGreen)
                        }
                        Button("➤") {
                            let text = input
                            input = ""
                            messages.append(SecureStorage.Message(dir: "out", peer: query, text: text))
                            Task {
                                let ok = await api.send(to: hash, payload: VeilEnvelope.text(text))
                                if !ok {
                                    messages.append(SecureStorage.Message(dir: "in", peer: query, text: "⚠ доставка не прошла"))
                                }
                            }
                        }.foregroundColor(VeilGreen)
                    }.padding()
                } else {
                    // Поиск собеседника
                    VStack(spacing: 12) {
                        TextField("Найти по юзернейму", text: $query)
                            .autocapitalization(.none).disableAutocorrection(true)
                            .textFieldStyle(.roundedBorder).padding(.horizontal, 30)
                        Button("Найти") {
                            Task { peerHash = await api.lookup(query.lowercased()) }
                        }.buttonStyle(.borderedProminent).tint(VeilGreen)
                        if peerHash == nil && !query.isEmpty {
                            Text("Введите юзернейм и нажмите «Найти»").font(.caption).foregroundColor(.gray)
                        }
                        Divider().padding(.horizontal, 30)
                        Button("👥 Новая группа") { groupSheet = true }
                            .foregroundColor(VeilGreen)
                        ForEach(VeilGroups.all.values.sorted { $0.name < $1.name }) { g in
                            Button { openGroup = g } label: {
                                HStack {
                                    Text("👥")
                                    Text(g.name).foregroundColor(.white)
                                    Spacer()
                                    Text("\(g.members.count) участников").font(.caption).foregroundColor(.gray)
                                }
                            }
                        }
                        Spacer()
                    }.padding(.top, 40)
                }
            }
            .navigationTitle("VEIL")
            .navigationBarTitleDisplayMode(.inline)
            .background(VeilBg)
            .sheet(isPresented: $groupSheet) {
                newGroupSheet
            }
            .fullScreenCover(isPresented: Binding(
                get: { callPeer != nil }, set: { if !$0 { callPeer = nil; callOfferSdp = nil } })) {
                if let p = callPeer {
                    CallView(api: api, peer: p, answerSdp: callOfferSdp,
                             onEnd: { callPeer = nil; callOfferSdp = nil })
                }
            }
            .fullScreenCover(isPresented: Binding(
                get: { incomingCall != nil }, set: { if !$0 { incomingCall = nil } })) {
                if let inc = incomingCall {
                    VStack(spacing: 20) {
                        Spacer()
                        Text("Входящий звонок").foregroundColor(.gray)
                        Text(inc.peer).font(.largeTitle).foregroundColor(.white)
                        Spacer()
                        HStack(spacing: 24) {
                            Button("Отклонить", role: .destructive) {
                                Task { _ = await api.send(to: inc.peer, payload: VeilEnvelope.callEnd("declined")) }
                                incomingCall = nil
                            }
                            Button("Ответить") {
                                callPeer = inc.peer; callOfferSdp = inc.sdp; incomingCall = nil
                            }
                        }
                        Spacer()
                    }.background(VeilBg)
                }
            }
            .onChange(of: pickedItem) { item in
                guard let item = item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        let payload = VeilEnvelope.media(type: MsgType.image, name: "photo.jpg", bytes: data)
                        let _ = await api.send(to: peerHash ?? "", payload: payload)
                        messages.append(SecureStorage.Message(dir: "out", peer: query, text: "🖼 [изображение \(data.count / 1024) КБ]"))
                    }
                    pickedItem = nil
                }
            }
            .task {
                // Фон-опрос реле: входящие звонки (call_offer / call_end).
                while true {
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    guard callPeer == nil, incomingCall == nil else { continue }
                    guard let envs = try? await api.fetchAll() else { continue }
                    for (from, payload) in envs {
                        guard let o = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
                              let type = o["type"] as? String else { continue }
                        if type == MsgType.callOffer, let sdp = o["sdp"] as? String {
                            incomingCall = (from, sdp)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func groupChatView(_ g: GroupInfo) -> some View {
        VStack(spacing: 0) {
            HStack {
                Button("←") { openGroup = nil }.foregroundColor(VeilGreen)
                Text(g.name).font(.headline).foregroundColor(.white)
                Spacer()
                Text("\(g.members.count) · E2E").font(.caption).foregroundColor(VeilGreen)
            }.padding()
            ScrollView {
                ForEach(messages.indices, id: \.self) { i in
                    let m = messages[i]
                    HStack {
                        if m.dir == "out" { Spacer() }
                        Text(m.text).padding(10)
                            .background(m.dir == "out" ? VeilGreen : VeilSurface)
                            .foregroundColor(m.dir == "out" ? .black : .white)
                            .cornerRadius(14)
                        if m.dir != "out" { Spacer() }
                    }.padding(.horizontal, 10).padding(.vertical, 2)
                }
            }
            HStack {
                TextField("Сообщение в группу…", text: $input)
                    .textFieldStyle(.roundedBorder)
                Button("➤") {
                    let text = input
                    input = ""
                    messages.append(SecureStorage.Message(dir: "out", peer: g.name, text: text))
                    let payload = VeilEnvelope.groupMsg(gid: g.id, body: text)
                    Task {
                        for m in g.members {
                            let ok = await api.send(to: m, payload: payload)
                            if !ok { messages.append(SecureStorage.Message(dir: "in", peer: g.name, text: "⚠ доставка не дошла: \(m)")); break }
                        }
                    }
                }.foregroundColor(VeilGreen)
            }.padding()
        }
    }

    private var newGroupSheet: some View {
        VStack(spacing: 12) {
            Text("Новая группа").font(.headline).padding(.top, 20)
            TextField("Название", text: $groupName)
                .textFieldStyle(.roundedBorder).padding(.horizontal, 20)
            HStack {
                TextField("Юзернейм участника", text: $newMember)
                    .autocapitalization(.none).disableAutocorrection(true)
                    .textFieldStyle(.roundedBorder)
                Button("+") {
                    let m = newMember.lowercased().trimmingCharacters(in: .whitespaces)
                    if !m.isEmpty && !groupMembers.contains(m) { groupMembers.append(m) }
                    newMember = ""
                }.foregroundColor(VeilGreen)
            }.padding(.horizontal, 20)
            ForEach(groupMembers, id: \.self) { Text("  " + $0).foregroundColor(.white) }
            Button("Создать") {
                let g = GroupInfo(id: VeilGroups.newId(), name: groupName.isEmpty ? "Группа" : groupName, members: groupMembers)
                VeilGroups.save(g)
                groupSheet = false
            }
            .disabled(groupMembers.isEmpty)
            .buttonStyle(.borderedProminent).tint(VeilGreen)
            Text("Название и состав шифруются вместе с сообщениями — реле их не знает.")
                .font(.caption).foregroundColor(.gray)
            Spacer()
        }
    }
}
