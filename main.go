package main

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// ────────────────────────────────────────────
// 数据结构
// ────────────────────────────────────────────

type Role string

const (
	RoleGuardian Role = "guardian" // 监护者
	RoleWard     Role = "ward"     // 被监护者
)

// Device 设备实体
type Device struct {
	UUID      string    `json:"uuid"`
	Role      Role      `json:"role"`
	Latitude  float64   `json:"latitude"`
	Longitude float64   `json:"longitude"`
	LastSeen  time.Time `json:"last_seen"`
}

// PairingSession 旧式配对会话（保留兼容）
type PairingSession struct {
	SessionID   string    `json:"session_id"`
	GuardianID  string    `json:"guardian_id"`
	WardID      string    `json:"ward_id"`
	CreatedAt   time.Time `json:"created_at"`
	IsConfirmed bool      `json:"is_confirmed"`
}

// PairingCode 临时配对码（被监护者生成，5分钟有效）
type PairingCode struct {
	Code      string
	WardID    string
	ExpiresAt time.Time
}

// Binding 已完成的双向绑定关系
type Binding struct {
	GuardianID string    `json:"guardian_id"`
	WardID     string    `json:"ward_id"`
	CreatedAt  time.Time `json:"created_at"`
}

// ────────────────────────────────────────────
// 内存存储
// ────────────────────────────────────────────

type Store struct {
	mu sync.RWMutex

	devices  map[string]*Device
	sessions map[string]*PairingSession

	// 配对码：code -> PairingCode
	pairingCodes map[string]*PairingCode
	// 每个 ward 只允许一个有效码，记录其当前 code（生成新码时自动废弃旧码）
	wardCode map[string]string
	// 绑定关系：uuid -> *Binding（guardian 和 ward 共享同一个指针，双向可查）
	bindings map[string]*Binding
}

func NewStore() *Store {
	return &Store{
		devices:      make(map[string]*Device),
		sessions:     make(map[string]*PairingSession),
		pairingCodes: make(map[string]*PairingCode),
		wardCode:     make(map[string]string),
		bindings:     make(map[string]*Binding),
	}
}

// ── Device ──

func (s *Store) GetDevice(id string) (*Device, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	d, ok := s.devices[id]
	return d, ok
}

func (s *Store) UpsertDevice(d *Device) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.devices[d.UUID] = d
}

// ── PairingSession（旧） ──

func (s *Store) GetSession(id string) (*PairingSession, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	ps, ok := s.sessions[id]
	return ps, ok
}

func (s *Store) CreateSession(ps *PairingSession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[ps.SessionID] = ps
}

func (s *Store) UpdateSession(ps *PairingSession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[ps.SessionID] = ps
}

// ── PairingCode ──

// StorePairingCode 为 ward 存储新配对码，自动废弃其旧码（如有）
func (s *Store) StorePairingCode(wardID, code string, expiry time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if old, ok := s.wardCode[wardID]; ok {
		delete(s.pairingCodes, old)
	}
	s.pairingCodes[code] = &PairingCode{Code: code, WardID: wardID, ExpiresAt: expiry}
	s.wardCode[wardID] = code
}

// ConsumePairingCode 校验并原子消费配对码，成功返回 wardID
func (s *Store) ConsumePairingCode(code string) (wardID string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	pc, ok := s.pairingCodes[code]
	if !ok {
		return "", fmt.Errorf("invalid pairing code")
	}
	if time.Now().After(pc.ExpiresAt) {
		delete(s.pairingCodes, code)
		delete(s.wardCode, pc.WardID)
		return "", fmt.Errorf("pairing code expired")
	}
	// 一次性消费，防止重放
	delete(s.pairingCodes, code)
	delete(s.wardCode, pc.WardID)
	return pc.WardID, nil
}

// ── Binding ──

func (s *Store) CreateBinding(guardianID, wardID string) *Binding {
	s.mu.Lock()
	defer s.mu.Unlock()
	b := &Binding{GuardianID: guardianID, WardID: wardID, CreatedAt: time.Now()}
	// 双向写入同一个指针，任意一方 uuid 均可查询
	s.bindings[guardianID] = b
	s.bindings[wardID] = b
	return b
}

func (s *Store) GetBinding(id string) (*Binding, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	b, ok := s.bindings[id]
	return b, ok
}

// ────────────────────────────────────────────
// 工具函数
// ────────────────────────────────────────────

// generateCode 用 crypto/rand 生成6位数字配对码（000000-999999）
func generateCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

// ────────────────────────────────────────────
// Handlers — 设备
// ────────────────────────────────────────────

// POST /devices/register
// Body: { "uuid": "...", "role": "guardian"|"ward" }
func registerDevice(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			UUID string `json:"uuid" binding:"required"`
			Role Role   `json:"role" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		if req.Role != RoleGuardian && req.Role != RoleWard {
			c.JSON(http.StatusBadRequest, gin.H{"error": "role must be 'guardian' or 'ward'"})
			return
		}
		device := &Device{UUID: req.UUID, Role: req.Role, LastSeen: time.Now()}
		store.UpsertDevice(device)
		c.JSON(http.StatusOK, device)
	}
}

// GET /devices/:uuid
func getDevice(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		device, ok := store.GetDevice(c.Param("uuid"))
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
			return
		}
		c.JSON(http.StatusOK, device)
	}
}

// PUT /devices/:uuid/location
// Body: { "latitude": 39.9, "longitude": 116.4 }
func updateLocation(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			Latitude  float64 `json:"latitude"  binding:"required"`
			Longitude float64 `json:"longitude" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		device, ok := store.GetDevice(c.Param("uuid"))
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
			return
		}
		device.Latitude = req.Latitude
		device.Longitude = req.Longitude
		device.LastSeen = time.Now()
		store.UpsertDevice(device)
		c.JSON(http.StatusOK, device)
	}
}

// ────────────────────────────────────────────
// Handlers — 旧式会话（保留）
// ────────────────────────────────────────────

// POST /sessions
func createSession(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			GuardianID string `json:"guardian_id" binding:"required"`
			WardID     string `json:"ward_id"     binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		ps := &PairingSession{
			SessionID:  uuid.NewString(),
			GuardianID: req.GuardianID,
			WardID:     req.WardID,
			CreatedAt:  time.Now(),
		}
		store.CreateSession(ps)
		c.JSON(http.StatusCreated, ps)
	}
}

// PUT /sessions/:id/confirm
func confirmSession(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		ps, ok := store.GetSession(c.Param("id"))
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
			return
		}
		ps.IsConfirmed = true
		store.UpdateSession(ps)
		c.JSON(http.StatusOK, ps)
	}
}

// GET /sessions/:id
func getSession(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		ps, ok := store.GetSession(c.Param("id"))
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
			return
		}
		c.JSON(http.StatusOK, ps)
	}
}

// ────────────────────────────────────────────
// Handlers — 双向配对（新）
// ────────────────────────────────────────────

// POST /pair/generate
// 被监护者调用，生成6位配对码（有效5分钟）
// Body: { "ward_uuid": "..." }
func generatePairing(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			WardUUID string `json:"ward_uuid" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		device, ok := store.GetDevice(req.WardUUID)
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
			return
		}
		if device.Role != RoleWard {
			c.JSON(http.StatusForbidden, gin.H{"error": "only ward devices can generate a pairing code"})
			return
		}
		if _, bound := store.GetBinding(req.WardUUID); bound {
			c.JSON(http.StatusConflict, gin.H{"error": "device is already paired"})
			return
		}

		code, err := generateCode()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate code"})
			return
		}

		expiry := time.Now().Add(5 * time.Minute)
		store.StorePairingCode(req.WardUUID, code, expiry)

		c.JSON(http.StatusOK, gin.H{
			"pairing_code": code,
			"expires_at":   expiry,
		})
	}
}

// POST /pair/confirm
// 监护者调用，输入配对码完成双向绑定
// Body: { "guardian_uuid": "...", "pairing_code": "123456" }
func confirmPairing(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			GuardianUUID string `json:"guardian_uuid" binding:"required"`
			PairingCode  string `json:"pairing_code"  binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		device, ok := store.GetDevice(req.GuardianUUID)
		if !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
			return
		}
		if device.Role != RoleGuardian {
			c.JSON(http.StatusForbidden, gin.H{"error": "only guardian devices can confirm pairing"})
			return
		}
		if _, bound := store.GetBinding(req.GuardianUUID); bound {
			c.JSON(http.StatusConflict, gin.H{"error": "device is already paired"})
			return
		}

		// 原子消费配对码：校验 + 过期检查 + 一次性删除
		wardID, err := store.ConsumePairingCode(req.PairingCode)
		if err != nil {
			c.JSON(http.StatusUnprocessableEntity, gin.H{"error": err.Error()})
			return
		}

		binding := store.CreateBinding(req.GuardianUUID, wardID)
		c.JSON(http.StatusOK, binding)
	}
}

// GET /pair/status?uuid=xxx
// 查询任意设备的绑定状态
func pairingStatus(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.Query("uuid")
		if id == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "uuid query param is required"})
			return
		}
		if _, ok := store.GetDevice(id); !ok {
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
			return
		}
		binding, ok := store.GetBinding(id)
		if !ok {
			c.JSON(http.StatusOK, gin.H{"paired": false})
			return
		}
		c.JSON(http.StatusOK, gin.H{"paired": true, "binding": binding})
	}
}

// ────────────────────────────────────────────
// main
// ────────────────────────────────────────────

func main() {
	store := NewStore()
	r := gin.Default()

	r.GET("/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "pong"})
	})

	devices := r.Group("/devices")
	{
		devices.POST("/register", registerDevice(store))
		devices.GET("/:uuid", getDevice(store))
		devices.PUT("/:uuid/location", updateLocation(store))
	}

	sessions := r.Group("/sessions")
	{
		sessions.POST("", createSession(store))
		sessions.GET("/:id", getSession(store))
		sessions.PUT("/:id/confirm", confirmSession(store))
	}

	pair := r.Group("/pair")
	{
		pair.POST("/generate", generatePairing(store))
		pair.POST("/confirm", confirmPairing(store))
		pair.GET("/status", pairingStatus(store))
	}

	r.Run(":8080")
}
