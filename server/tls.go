package main

// Самоподписанный TLS-сертификат реле. Генерируется один раз; отпечаток
// (SHA-256) клиент пинит при первом подключении. CA не нужен, домен не нужен.

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// Возврат: fingerprint (пусто, если уже существует — не печатаем повторно), err.
func loadOrMakeSelfSigned(certDir, certFile, keyFile string) (string, error) {
	if _, err := os.Stat(certFile); err == nil {
		return "", nil // уже создан — отпечаток хранится в certDir/fingerprint
	}
	if err := os.MkdirAll(certDir, 0700); err != nil {
		return "", err
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "veil-relay"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IPAddresses:           []net.IP{net.ParseIP("0.0.0.0").To4(), net.IPv6loopback},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, pub, priv)
	if err != nil {
		return "", err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return "", err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(certFile, certPEM, 0600); err != nil {
		return "", err
	}
	if err := os.WriteFile(keyFile, keyPEM, 0600); err != nil {
		return "", err
	}
	h := sha256.Sum256(der)
	return encodeFingerprint(h[:]), nil
}

func encodeFingerprint(b []byte) string {
	const hexdig = "0123456789abcdef"
	out := make([]byte, len(b)*2)
	for i, v := range b {
		out[i*2] = hexdig[v>>4]
		out[i*2+1] = hexdig[v&0x0f]
	}
	return string(out)
}

// Проверка на месте: при загрузке сертификата убеждаемся, что он читаем.
func ensureCertReadable(certFile, keyFile string) error {
	_, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return err
	}
	if filepath.Base(certFile) != "relay.pem" {
		return errors.New("неожиданное имя файла сертификата")
	}
	return nil
}
