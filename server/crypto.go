package main

// Криптопроверка подписей на сервере. Только публичный ключ Ed25519 —
// приватные ключи пользователей сервер никогда не видит.

import "crypto/ed25519"

const ed25519PubSize = ed25519.PublicKeySize // 32

func ed25519Verify(pub, msg, sig []byte) bool {
	if len(pub) != ed25519PubSize || len(sig) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(pub), msg, sig)
}
