# Job Agent — AI-powered SE job search pipeline

## What it does
1. Apify scrapes fresh job postings every hour from Workday portals, Greenhouse, Lever
2. This webhook receives the batch
3. Claude scores each job for fit and filters non-product companies
4. Every passing job is logged to Google Sheets
5. Jobs scoring 8+ send a Slack alert with a direct apply link

## Local setup


### 1. Clone and configure
```bash
cp .env.example .env
# Fill in your real values in .env
```

### 2. Google Service Account
1. Go to console.cloud.google.com → New project
2. Enable Google Sheets API
3. Create a Service Account → download JSON key
4. Share your Google Sheet with the service account email
5. Base64-encode the JSON: `cat key.json | base64`
6. Paste the raw JSON (not base64) into GOOGLE_SERVICE_ACCOUNT_JSON in .env

### 3. Google Sheet setup
Create a sheet named "Jobs" with these headers in row 1:
`Company | Title | Score | Product Co | Reason | URL | Date | Status`

### 4. Run locally
```bash
./gradlew bootRun
```
App starts on http://localhost:8080

### 5. Test without Apify
```bash
curl -X POST http://localhost:8080/api/test \
  -H "Content-Type: application/json" \
  -d '{
    "company": "Stripe",
    "title": "Senior Backend Engineer",
    "description": "We are looking for a senior engineer to work on our payments platform...",
    "url": "https://stripe.com/jobs/123",
    "location": "Remote"
  }'
```

## Apify setup

1. Sign up at apify.com
2. Add actor: `fantastic-jobs/workday-jobs-api`
3. Set schedule: every 1 hour
4. Set actor input:
```json
{
  "position": "Senior Software Engineer",
  "location": "Remote",
  "maxResults": 100,
  "timeRange": "1h"
}
```
5. Add webhook: Settings → Webhooks → Add
   - URL: https://your-railway-url.railway.app/api/apify-webhook
   - Event: Actor run succeeded

## Deploy to Railway

```bash
# Install Railway CLI
npm install -g @railway/cli

# Login and deploy
railway login
railway init
railway up
```

Set all env vars from .env in Railway dashboard → Variables.

## Project structure
```
src/main/java/com/jobagent/
├── JobAgentApplication.java       # Entry point
├── config/
│   └── AppConfig.java             # All env vars in one place
├── controller/
│   └── WebhookController.java     # /api/apify-webhook, /api/health, /api/test
├── service/
│   ├── JobProcessorService.java   # Orchestrates the pipeline
│   ├── ClaudeService.java         # Scores jobs via Anthropic API
│   ├── SheetsService.java         # Logs to Google Sheets
│   └── SlackService.java          # Sends alerts
└── model/
    ├── Job.java                   # Job posting data
    └── JobScore.java              # Claude's scoring result
```

## Next steps (future phases)
- Add deduplication (Redis or DB) so the same job isn't processed twice
- Add a feedback loop — log which jobs you applied to, re-weight Claude scoring
- Add vector memory (Pinecone / ChromaDB) for smarter profile matching
