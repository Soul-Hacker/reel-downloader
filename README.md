# 🎬 ReelSave — Instagram Reel Downloader

A full-stack **Spring Boot** web application that lets users download Instagram Reels, Posts, and Videos by simply pasting a link. Built with a clean UI, per-IP rate limiting, and powered by the Instagram Scraper Stable API via RapidAPI.

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen?style=flat-square&logo=springboot)
![RapidAPI](https://img.shields.io/badge/RapidAPI-Instagram%20Scraper-blue?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)

---

## ✨ Features

- 🔗 **Paste any Instagram URL** — Reels, Posts (`/p/`), or `/reels/` links
- 🎬 **Download Videos** — streamed directly through the server (no CORS issues)
- 🖼 **Download Thumbnails** — save the cover image of any reel
- ⚡ **Rate Limiting** — global (10 req/60s) + per-IP (5 req/60s) protection via Resilience4j
- 🔒 **SSRF Protection** — proxy only allows Instagram/Facebook CDN domains
- 📱 **Responsive UI** — works on desktop and mobile

---

## 🖥 Demo

> Run locally at **http://localhost:8080**

1. Paste an Instagram reel or post URL
2. Click **Fetch**
3. Click **Download Video** or **Download Thumbnail**

---

## 🏗 Project Structure

```
reel-downloader/
├── src/main/java/com/reeldown/
│   ├── ReelDownloaderApplication.java     # Main entry point
│   ├── config/
│   │   ├── AppConfig.java                 # RestTemplate, HttpClient, CORS
│   │   └── RateLimiterConfiguration.java  # Resilience4j rate limiter beans
│   ├── controller/
│   │   ├── WebController.java             # Serves the UI (GET /)
│   │   ├── ReelController.java            # REST API endpoints
│   │   └── GlobalExceptionHandler.java    # Centralised error handling
│   ├── model/
│   │   ├── ReelInfo.java                  # Media metadata model
│   │   ├── FetchRequest.java              # Request DTO
│   │   └── ApiResponse.java               # Generic response wrapper
│   └── service/
│       ├── ReelScraperService.java         # Instagram API integration
│       ├── ProxyDownloadService.java       # Media proxy/streaming
│       └── IpRateLimiterService.java       # Per-IP rate limiting
├── src/main/resources/
│   ├── application.properties             # Config (API key goes here)
│   └── templates/index.html               # Thymeleaf UI
├── Dockerfile                             # For cloud deployment
└── pom.xml
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Java JDK | 17+ | [adoptium.net](https://adoptium.net) |
| Maven | 3.8+ | [maven.apache.org](https://maven.apache.org/download.cgi) |
| RapidAPI Key | Free | [rapidapi.com](https://rapidapi.com) |

### 1. Get a Free RapidAPI Key

1. Sign up at [rapidapi.com](https://rapidapi.com) (free)
2. Subscribe to [Instagram Scraper Stable API](https://rapidapi.com) — free tier available
3. Copy your `X-RapidAPI-Key`

### 2. Configure the API Key

Open `src/main/resources/application.properties` and set:

```properties
rapidapi.key=YOUR_KEY_HERE
rapidapi.host=instagram-scraper-stable-api.p.rapidapi.com
rapidapi.base-url=https://instagram-scraper-stable-api.p.rapidapi.com
```

### 3. Build

```bash
mvn clean package -DskipTests
```

### 4. Run

```bash
java -jar target/reel-downloader-1.0.0.jar
```

### 5. Open in Browser

```
http://localhost:8080
```

> **Windows users:** use backslashes → `target\reel-downloader-1.0.0.jar`

---

## 📡 REST API Reference

### POST `/api/fetch`
Extract media metadata from an Instagram URL.

**Request:**
```json
{
  "url": "https://www.instagram.com/reels/DXoP6DEjHOl/"
}
```

**Response:**
```json
{
  "success": true,
  "rateLimitRemaining": 9,
  "data": {
    "videoUrl": "https://scontent.cdninstagram.com/...mp4",
    "imageUrl": "https://scontent.cdninstagram.com/...jpg",
    "caption": "Post caption here",
    "authorUsername": "username",
    "authorFullName": "Full Name",
    "postId": "DXoP6DEjHOl",
    "hasVideo": true,
    "hasImage": true,
    "extractedVia": "instagram-scraper-stable-api"
  }
}
```

**Error (rate limit):**
```json
{
  "success": false,
  "error": "Too many requests from your IP. Please wait 45 seconds."
}
```

---

### GET `/api/download`
Proxy-stream a media file as a download.

```
GET /api/download?url={mediaUrl}&filename=reel_video.mp4
```

Returns the file as `Content-Disposition: attachment`.

---

### GET `/api/status`
Check current rate limit status.

```json
{
  "global": { "available": 8, "limit": 10, "refresh": "60s" },
  "ip":     { "remaining": 4, "limit": 5, "resetSeconds": 32 }
}
```

---

### GET `/api/config-status`
Check if the RapidAPI key is configured.

```json
{
  "apiKeyConfigured": true,
  "message": "Ready — RapidAPI key is configured."
}
```

---

## ⚙️ Configuration Reference

All settings live in `src/main/resources/application.properties`:

```properties
# Server
server.port=8080

# RapidAPI (required)
rapidapi.key=YOUR_KEY_HERE
rapidapi.host=instagram-scraper-stable-api.p.rapidapi.com
rapidapi.base-url=https://instagram-scraper-stable-api.p.rapidapi.com

# Timeouts
scraper.connect-timeout-ms=12000
scraper.read-timeout-ms=20000

# Rate limiting — global
resilience4j.ratelimiter.instances.reelFetch.limit-for-period=10
resilience4j.ratelimiter.instances.reelFetch.limit-refresh-period=60s
resilience4j.ratelimiter.instances.reelFetch.timeout-duration=3s

# Logging
logging.level.com.reeldown=DEBUG
```

---

## 🛡 Rate Limiting

Two layers of protection:

| Layer | Limit | Scope | Implementation |
|-------|-------|-------|----------------|
| Global | 10 requests / 60s | Entire server | Resilience4j `RateLimiter` |
| Per-IP | 5 requests / 60s | Per client IP | `IpRateLimiterService` (ConcurrentHashMap) |

When exceeded, the API returns **HTTP 429** with a `Retry-After` header.

The per-IP tracker auto-cleans stale entries every 5 minutes via `@Scheduled` to prevent memory leaks.

---

## 🐳 Docker

Build and run with Docker:

```bash
# Build image
docker build -t reel-downloader .

# Run container
docker run -p 8080:8080 \
  -e RAPIDAPI_KEY=your_key_here \
  reel-downloader
```

> Make sure `application.properties` uses `${RAPIDAPI_KEY}` instead of the hardcoded key when deploying.

---

## ☁️ Deploy to Google Cloud Run

```bash
# Login
gcloud auth login
gcloud config set project YOUR_PROJECT_ID

# Deploy
gcloud run deploy reel-downloader \
  --source . \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --memory 512Mi \
  --set-env-vars RAPIDAPI_KEY=your_key_here
```

Your app will be live at:
```
https://reel-downloader-xxxx-uc.a.run.app
```

---

## 🧰 Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.2, Java 17 |
| Templating | Thymeleaf |
| HTTP Client | Java 11+ `HttpClient` |
| Rate Limiting | Resilience4j |
| JSON Parsing | Jackson |
| Instagram API | RapidAPI — Instagram Scraper Stable API |
| Frontend | Vanilla HTML/CSS/JS |
| Build | Maven |

---

## ⚠️ Legal Disclaimer

This tool is intended for **personal use only**. Users are solely responsible for ensuring they have the right to download any content. Downloading Instagram content may violate [Instagram's Terms of Service](https://help.instagram.com/581066165581870). The developer assumes no liability for misuse.

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

## 🙋 Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| `Could not extract media` | Wrong URL format or private post | Make sure post is public; use the full Instagram URL |
| `API key invalid` | Wrong or missing RapidAPI key | Check `rapidapi.key` in `application.properties` |
| `Port 8080 already in use` | Another process using the port | Run with `--server.port=9090` |
| `java: cannot find symbol` | Missing dependency | Run `mvn clean package -DskipTests` |
| `Host not in allowlist` | RapidAPI subscription missing | Subscribe to the API on RapidAPI dashboard |
