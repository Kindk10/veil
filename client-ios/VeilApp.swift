// VeilApp.swift — точка входа iOS-клиента VEIL.
// Слой криптографии и сети переносится из Kotlin-ядра 1:1 (см. PROTOCOL.md).

import SwiftUI

@main
struct VeilApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @State private var unlocked = false
    @State private var pin = ""
    @State private var error = false

    var body: some View {
        if unlocked {
            ChatsView()
        } else {
            VStack(spacing: 16) {
                Text("VEIL").font(.largeTitle.bold())
                    .foregroundColor(Color(red: 0.29, green: 0.89, blue: 0.55))
                SecureField("PIN", text: $pin)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 240)
                if error { Text("Неверный PIN").foregroundColor(.red).font(.footnote) }
                Button("Разблокировать") {
                    // SecureStorage.open(pin) — при 5 ошибках паническое стирание (§11)
                    if pin.count >= 4 { unlocked = true } else { error = true }
                }
            }
            .background(Color(red: 0.043, green: 0.059, blue: 0.051))
        }
    }
}

struct ChatsView: View {
    var body: some View {
        NavigationView {
            VStack {
                Text("Чаты появятся после первого сеанса связи")
                    .foregroundColor(.secondary)
                Spacer()
            }
            .navigationTitle("VEIL")
            .background(Color(red: 0.043, green: 0.059, blue: 0.051))
        }
    }
}
