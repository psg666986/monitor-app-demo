package main

import (
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/time/rate"
	_ "modernc.org/sqlite"
)

// ────────────────────────────────────────────
// 数据结构
// ────────────────────────────────────────────

type Role string

const (
	RoleGuardian Role = "guardian"
	RoleWard     Role = "ward"
)

// Device 设备实体
type Device struct {
	UUID       string     `json:"uuid"`
	Role       Role       `json:"role"`
	Latitude   float64    `json:"latitude"`
	Longitude  float64    `json:"longitude"`
	LastSeen   time.Time  `json:"last_seen"`
	LastUsedAt *time.Time `json:"last_used_at"`
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
// JWT
// ────────────────────────────────────────────

var jwtSecret []byte

// DeviceClaims JWT payload：设备身份
type DeviceClaims struct {
	UUID string `json:"uuid"`
	Role Role   `json:"role"`
	jwt.RegisteredClaims
}

// generateToken 为设备签发 JWT（有效期 1 年）
func generateToken(uuid string, role Role) (string, error) {
	claims := DeviceClaims{
		UUID: uuid,
		Role: role,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(365 * 24 * time.Hour)),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(jwtSecret)
}

// corsMiddleware 允许前端跨域访问（开发环境 Vite dev server / 生产环境独立部署）
func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

// authMiddleware 校验 Authorization: Bearer <token>，通过后将 uuid/role 注入 gin.Context
func authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized,
				gin.H{"error": "missing or invalid authorization header"})
			return
		}

		claims := &DeviceClaims{}
		token, err := jwt.ParseWithClaims(
			strings.TrimPrefix(header, "Bearer "),
			claims,
			func(t *jwt.Token) (interface{}, error) {
				if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
					return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
				}
				return jwtSecret, nil
			},
		)
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}
		c.Set("uuid", claims.UUID)
		c.Set("role", string(claims.Role))
		c.Next()
	}
}

// ────────────────────────────────────────────
// SQLite 存储
// ────────────────────────────────────────────

type Store struct {
	db *sql.DB
}

func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

// initDB 创建数据表，开启 WAL 模式提升并发读性能
func initDB(db *sql.DB) error {
	stmts := []string{
		`PRAGMA journal_mode=WAL`,
		`PRAGMA foreign_keys=ON`,
		`CREATE TABLE IF NOT EXISTS devices (
			uuid         TEXT PRIMARY KEY,
			role         TEXT NOT NULL,
			latitude     REAL NOT NULL DEFAULT 0,
			longitude    REAL NOT NULL DEFAULT 0,
			last_seen    TEXT NOT NULL,
			last_used_at TEXT
		)`,
		`CREATE TABLE IF NOT EXISTS bindings (
			guardian_id TEXT NOT NULL UNIQUE,
			ward_id     TEXT NOT NULL UNIQUE,
			created_at  TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS pairing_codes (
			code       TEXT PRIMARY KEY,
			ward_id    TEXT NOT NULL UNIQUE,
			expires_at TEXT NOT NULL
		)`,
	}
	for _, stmt := range stmts {
		if _, err := db.Exec(stmt); err != nil {
			return fmt.Errorf("initDB: %w", err)
		}
	}
	return nil
}

// parseTime 解析 RFC3339 时间字符串，解析失败返回零值
func parseTime(s string) time.Time {
	t, _ := time.Parse(time.RFC3339, s)
	return t
}

// ── Device ──

func (s *Store) GetDevice(uuid string) (*Device, bool) {
	var d Device
	var lastSeen string
	var lastUsedAt sql.NullString

	err := s.db.QueryRow(
		`SELECT uuid, role, latitude, longitude, last_seen, last_used_at
		 FROM devices WHERE uuid = ?`, uuid,
	).Scan(&d.UUID, &d.Role, &d.Latitude, &d.Longitude, &lastSeen, &lastUsedAt)

	if errors.Is(err, sql.ErrNoRows) {
		return nil, false
	}
	if err != nil {
		log.Printf("GetDevice error: %v", err)
		return nil, false
	}
	d.LastSeen = parseTime(lastSeen)
	if lastUsedAt.Valid {
		t := parseTime(lastUsedAt.String)
		d.LastUsedAt = &t
	}
	return &d, true
}

// UpsertDevice 注册或更新设备（角色可覆盖，坐标保留）
func (s *Store) UpsertDevice(d *Device) error {
	_, err := s.db.Exec(
		`INSERT INTO devices (uuid, role, latitude, longitude, last_seen)
		 VALUES (?, ?, 0, 0, ?)
		 ON CONFLICT(uuid) DO UPDATE SET
		     role      = excluded.role,
		     last_seen = excluded.last_seen`,
		d.UUID, string(d.Role), d.LastSeen.Format(time.RFC3339),
	)
	return err
}

// UpdateDeviceData 原子更新 ward 设备的坐标和最后使用时间，消除 TOCTOU 竞态
func (s *Store) UpdateDeviceData(uuid string, lat, lng float64, lastUsedAt time.Time) error {
	_, err := s.db.Exec(
		`UPDATE devices SET latitude = ?, longitude = ?, last_seen = ?, last_used_at = ?
		 WHERE uuid = ?`,
		lat, lng, time.Now().Format(time.RFC3339), lastUsedAt.Format(time.RFC3339), uuid,
	)
	return err
}

// ── PairingCode ──

// StorePairingCode 原子替换 ward 的配对码（旧码自动废弃）
func (s *Store) StorePairingCode(wardID, code string, expiry time.Time) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback() //nolint:errcheck

	if _, err := tx.Exec(`DELETE FROM pairing_codes WHERE ward_id = ?`, wardID); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`INSERT INTO pairing_codes (code, ward_id, expires_at) VALUES (?, ?, ?)`,
		code, wardID, expiry.Format(time.RFC3339),
	); err != nil {
		return err
	}
	return tx.Commit()
}

// ConsumePairingCode 原子校验并删除配对码，返回 wardID
func (s *Store) ConsumePairingCode(code string) (string, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback() //nolint:errcheck

	var wardID, expiresAtStr string
	err = tx.QueryRow(
		`SELECT ward_id, expires_at FROM pairing_codes WHERE code = ?`, code,
	).Scan(&wardID, &expiresAtStr)
	if errors.Is(err, sql.ErrNoRows) {
		return "", fmt.Errorf("invalid pairing code")
	}
	if err != nil {
		return "", err
	}

	if time.Now().After(parseTime(expiresAtStr)) {
		tx.Exec(`DELETE FROM pairing_codes WHERE code = ?`, code) //nolint:errcheck
		tx.Commit()                                                //nolint:errcheck
		return "", fmt.Errorf("pairing code expired")
	}

	if _, err := tx.Exec(`DELETE FROM pairing_codes WHERE code = ?`, code); err != nil {
		return "", err
	}
	return wardID, tx.Commit()
}

// CleanExpiredCodes 删除所有已过期配对码（后台定时调用）
func (s *Store) CleanExpiredCodes() {
	if _, err := s.db.Exec(
		`DELETE FROM pairing_codes WHERE expires_at < ?`, time.Now().Format(time.RFC3339),
	); err != nil {
		log.Printf("CleanExpiredCodes error: %v", err)
	}
}

// ── Binding ──

func (s *Store) CreateBinding(guardianID, wardID string) (*Binding, error) {
	now := time.Now()
	_, err := s.db.Exec(
		`INSERT INTO bindings (guardian_id, ward_id, created_at) VALUES (?, ?, ?)`,
		guardianID, wardID, now.Format(time.RFC3339),
	)
	if err != nil {
		return nil, err
	}
	return &Binding{GuardianID: guardianID, WardID: wardID, CreatedAt: now}, nil
}

// GetBinding 通过 guardian 或 ward 的 UUID 查询绑定关系
func (s *Store) GetBinding(id string) (*Binding, bool) {
	var b Binding
	var createdAt string
	err := s.db.QueryRow(
		`SELECT guardian_id, ward_id, created_at FROM bindings
		 WHERE guardian_id = ? OR ward_id = ?`, id, id,
	).Scan(&b.GuardianID, &b.WardID, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false
	}
	if err != nil {
		log.Printf("GetBinding error: %v", err)
		return nil, false
	}
	b.CreatedAt = parseTime(createdAt)
	return &b, true
}

// ────────────────────────────────────────────
// 工具函数
// ────────────────────────────────────────────

// uuidRegex 预编译，匹配标准 UUID v4 格式（小写，含连字符）
var uuidRegex = regexp.MustCompile(
	`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`,
)

func isValidUUID(s string) bool { return uuidRegex.MatchString(s) }

// ── Per-IP 限速（令牌桶） ──────────────────────────────────

type ipLimiter struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

var (
	ipLimiters sync.Map        // map[string]*ipLimiter
	// 每 IP 每分钟最多 10 次（针对配对码暴力破解保护）
	rateLimit  = rate.Every(6 * time.Second) // 10 req/min
	rateBurst  = 10
)

// getLimiter 返回该 IP 对应的令牌桶，不存在则创建
func getLimiter(ip string) *rate.Limiter {
	v, _ := ipLimiters.LoadOrStore(ip, &ipLimiter{
		limiter:  rate.NewLimiter(rateLimit, rateBurst),
		lastSeen: time.Now(),
	})
	l := v.(*ipLimiter)
	l.lastSeen = time.Now()
	return l.limiter
}

// rateLimitMiddleware 对指定路由施加 per-IP 限速，超限返回 429
func rateLimitMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !getLimiter(c.ClientIP()).Allow() {
			c.AbortWithStatusJSON(http.StatusTooManyRequests,
				gin.H{"error": "too many requests, please try again later"})
			return
		}
		c.Next()
	}
}

// generateCode 用 crypto/rand 生成 6 位数字配对码
func generateCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

// ────────────────────────────────────────────
// Handlers — 设备（公开路由）
// ────────────────────────────────────────────

// POST /devices/register（公开，无需 token）
// Body: { "uuid": "...", "role": "guardian"|"ward" }
// Response: { "device": {...}, "token": "..." }
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
		if !isValidUUID(req.UUID) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "uuid must be a valid UUID v4 (lowercase, hyphenated)"})
			return
		}

		device := &Device{UUID: req.UUID, Role: req.Role, LastSeen: time.Now()}
		if err := store.UpsertDevice(device); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to register device"})
			return
		}

		token, err := generateToken(req.UUID, req.Role)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate token"})
			return
		}

		c.JSON(http.StatusOK, gin.H{"device": device, "token": token})
	}
}

// ────────────────────────────────────────────
// Handlers — 配对（受保护路由）
// ────────────────────────────────────────────

// POST /pair/generate（受保护，仅 ward 调用）
// 无需 request body；ward UUID 从 JWT claims 获取
func generatePairing(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		uuid := c.GetString("uuid")
		if Role(c.GetString("role")) != RoleWard {
			c.JSON(http.StatusForbidden, gin.H{"error": "only ward devices can generate a pairing code"})
			return
		}
		if _, bound := store.GetBinding(uuid); bound {
			c.JSON(http.StatusConflict, gin.H{"error": "device is already paired"})
			return
		}

		code, err := generateCode()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate code"})
			return
		}

		expiry := time.Now().Add(5 * time.Minute)
		if err := store.StorePairingCode(uuid, code, expiry); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to store pairing code"})
			return
		}

		c.JSON(http.StatusOK, gin.H{"pairing_code": code, "expires_at": expiry})
	}
}

// POST /pair/confirm（受保护，仅 guardian 调用）
// Body: { "pairing_code": "123456" }
func confirmPairing(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			PairingCode string `json:"pairing_code" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		uuid := c.GetString("uuid")
		if Role(c.GetString("role")) != RoleGuardian {
			c.JSON(http.StatusForbidden, gin.H{"error": "only guardian devices can confirm pairing"})
			return
		}
		if _, bound := store.GetBinding(uuid); bound {
			c.JSON(http.StatusConflict, gin.H{"error": "device is already paired"})
			return
		}

		wardID, err := store.ConsumePairingCode(req.PairingCode)
		if err != nil {
			c.JSON(http.StatusUnprocessableEntity, gin.H{"error": err.Error()})
			return
		}

		binding, err := store.CreateBinding(uuid, wardID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create binding"})
			return
		}
		c.JSON(http.StatusOK, binding)
	}
}

// GET /pair/status（受保护）
// 返回当前认证设备的配对状态
func pairingStatus(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		binding, ok := store.GetBinding(c.GetString("uuid"))
		if !ok {
			c.JSON(http.StatusOK, gin.H{"paired": false})
			return
		}
		c.JSON(http.StatusOK, gin.H{"paired": true, "binding": binding})
	}
}

// ────────────────────────────────────────────
// Handlers — 数据同步（受保护路由）
// ────────────────────────────────────────────

// POST /data/update（受保护，仅 ward 调用）
// Body: { "lat": 39.9, "lng": 116.4, "last_used_at": "2006-01-02T15:04:05Z" }
// uuid 从 JWT claims 获取，消除 TOCTOU 竞态
func dataUpdate(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			Lat        float64   `json:"lat"          binding:"required"`
			Lng        float64   `json:"lng"          binding:"required"`
			LastUsedAt time.Time `json:"last_used_at" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		uuid := c.GetString("uuid")
		if Role(c.GetString("role")) != RoleWard {
			c.JSON(http.StatusForbidden, gin.H{"error": "only ward devices can call this endpoint"})
			return
		}
		if _, bound := store.GetBinding(uuid); !bound {
			c.JSON(http.StatusForbidden, gin.H{"error": "device is not paired"})
			return
		}

		if err := store.UpdateDeviceData(uuid, req.Lat, req.Lng, req.LastUsedAt); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update data"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"message": "updated"})
	}
}

// GET /data/latest（受保护，仅 guardian 调用）
// guardianUUID 从 JWT claims 获取
func dataLatest(store *Store) gin.HandlerFunc {
	return func(c *gin.Context) {
		uuid := c.GetString("uuid")
		if Role(c.GetString("role")) != RoleGuardian {
			c.JSON(http.StatusForbidden, gin.H{"error": "only guardian devices can call this endpoint"})
			return
		}

		binding, bound := store.GetBinding(uuid)
		if !bound {
			c.JSON(http.StatusNotFound, gin.H{"error": "no pairing found for this guardian"})
			return
		}

		ward, ok := store.GetDevice(binding.WardID)
		if !ok {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "ward device record missing"})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"ward_uuid":    ward.UUID,
			"latitude":     ward.Latitude,
			"longitude":    ward.Longitude,
			"last_seen":    ward.LastSeen,
			"last_used_at": ward.LastUsedAt,
		})
	}
}

// ────────────────────────────────────────────
// main
// ────────────────────────────────────────────

func main() {
	// JWT 密钥从环境变量读取，缺失则启动失败（快速失败原则）
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		log.Fatal("JWT_SECRET environment variable is required")
	}
	jwtSecret = []byte(secret)

	// SQLite 数据库路径，默认 guardian.db
	dbPath := os.Getenv("DB_PATH")
	if dbPath == "" {
		dbPath = "guardian.db"
	}
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		log.Fatalf("failed to open database: %v", err)
	}
	defer db.Close()

	if err := initDB(db); err != nil {
		log.Fatalf("failed to initialize database: %v", err)
	}

	store := NewStore(db)

	// 后台定时：清理过期配对码（每分钟）+ 清理不活跃 IP limiter（每10分钟）
	go func() {
		codeTicker := time.NewTicker(time.Minute)
		ipTicker   := time.NewTicker(10 * time.Minute)
		defer codeTicker.Stop()
		defer ipTicker.Stop()
		for {
			select {
			case <-codeTicker.C:
				store.CleanExpiredCodes()
			case <-ipTicker.C:
				cutoff := time.Now().Add(-10 * time.Minute)
				ipLimiters.Range(func(k, v any) bool {
					if v.(*ipLimiter).lastSeen.Before(cutoff) {
						ipLimiters.Delete(k)
					}
					return true
				})
			}
		}
	}()

	r := gin.Default()
	r.Use(corsMiddleware())

	r.GET("/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "pong"})
	})

	// 公开路由（无需 token）
	r.POST("/devices/register", registerDevice(store))

	// 受保护路由（需要有效 JWT）
	auth := r.Group("/")
	auth.Use(authMiddleware())
	{
		pair := auth.Group("/pair")
		{
			pair.POST("/generate", generatePairing(store))
			// /confirm 施加 per-IP 限速，防止暴力穷举 6 位配对码
			pair.POST("/confirm", rateLimitMiddleware(), confirmPairing(store))
			pair.GET("/status", pairingStatus(store))
		}

		data := auth.Group("/data")
		{
			data.POST("/update", dataUpdate(store))
			data.GET("/latest", dataLatest(store))
		}
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	r.Run(":" + port)
}
