// VEIL relay server.
// Принципы: сервер не расшифровывает, не логирует IP, не хранит историю
// после доставки, не имеет механизма восстановления аккаунта.
package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"

	"golang.org/x/crypto/acme/autocert"
)

type Envelope struct {
	To     string `json:"to"`     // H(ID) получателя (hex)
	From   string `json:"from"`   // H(ID) отправителя (hex)
	Payload []byte `json:"payload"` // ratchet-шифр, непрозрачно для сервера
	ReceivedAt time.Time `json:"-"` // только для TTL-очистки очереди, не логируется
}

type Account struct {
	IDHash    string // hex(SHA-256(ID))
	PubKey    []byte // IKp (Ed25519) — публичный, перехват безвреден
	SPK       []byte // signed prekey
	SPKSig    []byte
	OTKs      [][]byte // одноразовые prekey
	Username  string   // "" если не задан
	ErasePub   []byte   // отдельный erase-токен (Keystore-ключ клиента)
	EraseToken []byte   // 32 байта случайного токена для /erase при неверном PIN
	Queue      []*Envelope
}

type Store struct {
	mu       sync.RWMutex
	byIDHash map[string]*Account   // H(ID) -> аккаунт
	byUser   map[string]string     // username -> H(ID)
}

func NewStore() *Store {
	s := &Store{byIDHash: map[string]*Account{}, byUser: map[string]string{}}
	// TTL-уборка: конверты старше 24 ч стираются безвозвратно (§5.2,
	// self-destruct по умолчанию): окно метаданных-снимка ограничено.
	go func() {
		for {
			time.Sleep(10 * time.Minute)
			cutoff := time.Now().Add(-24 * time.Hour)
			s.mu.Lock()
			for _, acc := range s.byIDHash {
				kept := acc.Queue[:0]
				for _, env := range acc.Queue {
					if env.ReceivedAt.After(cutoff) {
						kept = append(kept, env)
					} else {
						for i := range env.Payload {
							env.Payload[i] = 0
						}
					}
				}
				acc.Queue = kept
			}
			s.mu.Unlock()
		}
	}()
	return s
}

func (s *Store) hashID(id []byte) string {
	h := sha256.Sum256(id)
	return hex.EncodeToString(h[:])
}

// verifyAuth: подпись IKa над (method|path|sha256(body)). Сервер проверяет
// по публичному ключу; паролей и сессийных токенов нет.
func verifyAuth(r *http.Request, body []byte, s *Store) (*Account, bool) {
	idHash := r.Header.Get("X-ID-Hash")
	sigB64 := r.Header.Get("X-Auth-Sig")
	if idHash == "" || sigB64 == "" {
		return nil, false
	}
	s.mu.RLock()
	acc := s.byIDHash[idHash]
	s.mu.RUnlock()
	if acc == nil {
		return nil, false
	}
	bodyHash := sha256.Sum256(body)
	msg := append([]byte(r.Method+r.URL.Path), bodyHash[:]...)
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if err != nil {
		return nil, false
	}
	// Подпись может быть от identity-ключа ИЛИ от erase-токена
	// (токен имеет право только на стирание, см. hErase).
	if ed25519Verify(acc.PubKey, msg, sig) {
		return acc, true
	}
	if r.URL.Path == "/erase" && ed25519Verify(acc.ErasePub, msg, sig) {
		return acc, true
	}
	return nil, false
}

func main() {
	store := NewStore()

	mux := http.NewServeMux()
	mux.HandleFunc("POST /register", hRegister(store))
	mux.HandleFunc("POST /prekeys", hPrekeys(store))
	mux.HandleFunc("GET /prekeys/{idhash}", hGetPrekey(store))
	mux.HandleFunc("POST /send", hSend(store))
	mux.HandleFunc("GET /fetch", hFetch(store))
	mux.HandleFunc("POST /ack", hAck(store))
	mux.HandleFunc("POST /username", hUsername(store))
	mux.HandleFunc("GET /lookup/{username}", hLookup(store))
	mux.HandleFunc("POST /erase", hErase(store)) // сертификат стирания
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not found", 404)
	})

	// ВАЖНО: никакого middleware логирования, никакого access.log.

	domain := os.Getenv("VEIL_DOMAIN") // например veil.example.org
	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// Режим VEIL_TLS=1: сам TLS с самоподписанным сертификатом.
	// Сертификат генерируется один раз и сохраняется в VEIL_CERT_DIR;
	// его SHA-256 отпечаток печатается ОДИН раз при создании — клиент
	// запоминает (пиннинг) и далее отвергает любые подмены.
	if envOr("VEIL_TLS", "") == "1" {
		certDir := envOr("VEIL_CERT_DIR", "./tls")
		certFile, keyFile := certDir+"/relay.pem", certDir+"/relay.key"
		var cert tls.Certificate
		fingerprint, err := loadOrMakeSelfSigned(certDir, certFile, keyFile)
		if err != nil {
			panic(err)
		}
		cert, err = tls.LoadX509KeyPair(certFile, keyFile)
		if err != nil {
			panic(err)
		}
		srv.Addr = ":443"
		srv.TLSConfig = &tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS13,
		}
		if fingerprint != "" {
			// Печатается один раз, затем файл fp хранится локально.
			fmt.Printf("VEIL-TLS-FP:%s\n", fingerprint)
			_ = os.WriteFile(certDir+"/fingerprint", []byte(fingerprint), 0600)
		}
	if err := srv.ListenAndServeTLS("", ""); err != nil {
		panic(err)
	}
	return
	}

	if domain == "" {
		// Режим за TLS-терминатором (Caddy/nginx) или за onion-сервисом tor.
		srv.Addr = "127.0.0.1:8080"
		if err := srv.ListenAndServe(); err != nil {
			panic(err)
		}
		return
	}

	// Режим с автоматическим сертификатом Let's Encrypt (HTTP-01 challenge).
	// TLS 1.3, цепочка обновляется сама, кэш сертификатов — в VEIL_CERT_DIR.
	cfg := &autocert.Manager{
		Prompt:     autocert.AcceptTOS,
		HostPolicy: autocert.HostWhitelist(domain),
		Cache:      autocert.DirCache(envOr("VEIL_CERT_DIR", "./certs")),
	}
	srv.Addr = ":443"
	srv.TLSConfig = cfg.TLSConfig()
	srv.TLSConfig.MinVersion = tls.VersionTLS13

	go func() {
		acme := &http.Server{
			Addr:              ":80",
			Handler:           cfg.HTTPHandler(nil),
			ReadHeaderTimeout: 10 * time.Second,
		}
		_ = acme.ListenAndServe() // только для получения/обновления сертификата
	}()
	if err := srv.ListenAndServeTLS("", ""); err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func hRegister(s *Store) http.HandlerFunc {
	type req struct {
		IDHash   string   `json:"id_hash"`
		PubKey   []byte   `json:"pubkey"`
		SPK      []byte   `json:"spk"`
		SPKSig   []byte   `json:"spk_sig"`
		OTKs     [][]byte `json:"otks"`
		ErasePub []byte   `json:"erase_pub"`
		EraseTok []byte   `json:"erase_token"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var q req
		if err := json.NewDecoder(r.Body).Decode(&q); err != nil {
			http.Error(w, "bad request", 400)
			return
		}
		if len(q.PubKey) != ed25519PubSize || len(q.IDHash) != 64 {
			http.Error(w, "bad identity", 400)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if _, exists := s.byIDHash[q.IDHash]; exists {
			http.Error(w, "exists", 409)
			return
		}
		s.byIDHash[q.IDHash] = &Account{
			IDHash: q.IDHash, PubKey: q.PubKey, SPK: q.SPK, SPKSig: q.SPKSig,
			OTKs: q.OTKs, ErasePub: q.ErasePub, EraseToken: q.EraseTok,
		}
		w.WriteHeader(http.StatusCreated)
	}
}

func hPrekeys(s *Store) http.HandlerFunc {
	type req struct {
		OTKs [][]byte `json:"otks"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		body, _ := readBody(r)
		acc, ok := verifyAuth(r, body, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		var q req
		json.Unmarshal(body, &q)
		s.mu.Lock()
		acc.OTKs = append(acc.OTKs, q.OTKs...)
		s.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	}
}

func hGetPrekey(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		idhash := r.PathValue("idhash")
		s.mu.Lock()
		defer s.mu.Unlock()
		acc := s.byIDHash[idhash]
		if acc == nil {
			http.Error(w, "not found", 404)
			return
		}
		var otk []byte
		if len(acc.OTKs) > 0 {
			otk = acc.OTKs[0]
			acc.OTKs = acc.OTKs[1:] // одноразовый: выдаём и удаляем
		}
		writeJSON(w, map[string]any{"pubkey": acc.PubKey, "spk": acc.SPK, "spk_sig": acc.SPKSig, "otk": otk})
	}
}

func hSend(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, _ := readBody(r)
		_, ok := verifyAuth(r, body, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		var env Envelope
		if err := json.Unmarshal(body, &env); err != nil {
			http.Error(w, "bad request", 400)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		dst := s.byIDHash[env.To]
		if dst == nil {
			http.Error(w, "not found", 404)
			return
		}
		env.ReceivedAt = time.Now() // для TTL, никогда не отдаётся и не логируется
		dst.Queue = append(dst.Queue, &env)
		w.WriteHeader(http.StatusAccepted)
	}
}

func hFetch(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		acc, ok := verifyAuth(r, nil, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		batch := acc.Queue
		acc.Queue = nil
		if batch == nil {
			batch = []*Envelope{}
		}
		writeJSON(w, batch)
	}
}

func hAck(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		_, ok := verifyAuth(r, nil, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		// ACK не нужен: hFetch уже удалил конверты из очереди.
		// Оставлен как пустышка протокола для будущей гарантии доставки.
		w.WriteHeader(http.StatusNoContent)
	}
}

func hUsername(s *Store) http.HandlerFunc {
	type req struct {
		Username string `json:"username"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		body, _ := readBody(r)
		acc, ok := verifyAuth(r, body, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		var q req
		json.Unmarshal(body, &q)
		s.mu.Lock()
		defer s.mu.Unlock()
		if owner, taken := s.byUser[q.Username]; taken && owner != acc.IDHash {
			http.Error(w, "taken", 409)
			return
		}
		if acc.Username != "" {
			delete(s.byUser, acc.Username)
		}
		acc.Username = q.Username
		s.byUser[q.Username] = acc.IDHash
		w.WriteHeader(http.StatusNoContent)
	}
}

func hLookup(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		username := r.PathValue("username")
		s.mu.RLock()
		idhash := s.byUser[username]
		acc := s.byIDHash[idhash]
		var pubkey, spk []byte
		if acc != nil {
			pubkey, spk = acc.PubKey, acc.SPK
		}
		s.mu.RUnlock()
		if acc == nil {
			http.Error(w, "not found", 404)
			return
		}
		writeJSON(w, map[string]any{"id_hash": idhash, "pubkey": pubkey, "spk": spk})
	}
}

// hErase — сертификат стирания. Принимает два способа авторизации:
// 1) подпись IKa/erase-ключа (обычное удаление изнутри приложения);
// 2) заголовок X-Erase-Token — 32 случайных байта, выданных при регистрации
//    и живущих в Android Keystore. Работает даже при неверно введённом PIN
//    (5 неудачных попыток → принудительное стирание).
func hErase(s *Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		idHash := r.Header.Get("X-ID-Hash")
		if tok := r.Header.Get("X-Erase-Token"); tok != "" {
			s.mu.Lock()
			acc := s.byIDHash[idHash]
			valid := false
			if acc != nil && len(acc.EraseToken) == 32 {
				dec, err := hex.DecodeString(tok)
				if err == nil && subtle.ConstantTimeCompare(dec, acc.EraseToken) == 1 {
					valid = true
				}
			}
			if !valid {
				s.mu.Unlock()
				http.Error(w, "unauthorized", 401)
				return
			}
			// стирание — тот же путь, что и подписанный
			if acc.Username != "" {
				delete(s.byUser, acc.Username)
			}
			delete(s.byIDHash, acc.IDHash)
			for _, env := range acc.Queue {
				for i := range env.Payload {
					env.Payload[i] = 0
				}
			}
			acc.Queue = nil
			acc.PubKey, acc.SPK, acc.OTKs, acc.EraseToken, acc.ErasePub = nil, nil, nil, nil, nil
			s.mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
			return
		}
		body, _ := readBody(r)
		acc, ok := verifyAuth(r, body, s)
		if !ok {
			http.Error(w, "unauthorized", 401)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if acc.Username != "" {
			delete(s.byUser, acc.Username)
		}
		delete(s.byIDHash, acc.IDHash)
		// Целостный wipe: список Queue аккаунта разыменовывается и
		// перезаписывается нулями до удаления ссылок.
		for _, env := range acc.Queue {
			for i := range env.Payload {
				env.Payload[i] = 0
			}
		}
		acc.Queue = nil
		acc.PubKey, acc.SPK, acc.OTKs, acc.EraseToken, acc.ErasePub = nil, nil, nil, nil, nil
		w.WriteHeader(http.StatusNoContent)
	}
}

// --- утилиты ---

func readBody(r *http.Request) ([]byte, error) {
	defer r.Body.Close()
	buf := make([]byte, 1<<20) // максимум 1 МБ
	n, err := r.Body.Read(buf)
	if err != nil && n == 0 {
		return nil, err
	}
	return buf[:n], nil
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

// constantTimeEqual для сравнения строк-хешей, где это уместно
func ctEqual(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }
