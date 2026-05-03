# Trading Platform Starter: IBKR + Cache + ML + Excel

This starter project wires together:

- Java Spring Boot orchestration service
- Interactive Brokers TWS / IB Gateway historical bar ingestion
- incremental local JSON cache
- real technical indicators (EMA, RSI, MACD, ADX, VWAP)
- Python FastAPI model service
- Excel export to `predictions_summary_out_nextgen.xlsx`

## What is included

### Java

- full pipeline runner
- IBKR historical bar client
- incremental cache refresh
- feature computation
- ML HTTP client
- scoring
- Excel writer
- YAML-driven config and output columns

### Python

- FastAPI `/health`
- FastAPI `/predict`
- simple baseline predictor you can later replace with XGBoost / DL model

## Important note on the IBKR Java jar

The official TWS API is distributed by Interactive Brokers for Java, and the TWS API is a TCP-socket API that connects
through TWS or IB Gateway. IBKR’s docs also describe the classic asynchronous model using `EClientSocket`, `EReader`,
and `EWrapper`, and the historical bar request flow using `reqHistoricalData`, `historicalData`, and
`historicalDataEnd`. citeturn325362search10turn218483view0turn165670view0

Because the Java API jar distribution/versioning can vary by environment, this project is configured to expect a local
jar here:

```text
java/trading-api/lib/TwsApi.jar
```

Drop your official IBKR Java API jar there before building.

## TWS / Gateway settings

In TWS or Gateway:

- enable API socket connections
- whitelist localhost if needed
- ensure port matches your YAML config
- use a unique `clientId`

IBKR documents that multiple API clients can connect and that a unique client ID is required; requests should generally
wait until the connection handshake is complete, often via `nextValidId`. citeturn218483view0

## Build Java

```bash
cd java/trading-api
mvn clean package
```

## Run Python model service

```bash
cd python/model_service
uvicorn app.main:app --reload --port 8000
```

## Run Java API

```bash
cd java/trading-api
mvn spring-boot:run
```

## Trigger the pipeline

```bash
curl -X POST http://localhost:8081/api/pipeline/run
```

Output file:

```text
predictions_summary_out_nextgen.xlsx
```

## Historical data behavior

The IBKR historical request uses:

- `reqHistoricalData`
- `whatToShow=TRADES`
- `barSize=1 day`
- `formatDate=1`
- `keepUpToDate=false`

IBKR’s docs say historical bars are requested through `reqHistoricalData`, delivered in `historicalData`, and completed
with `historicalDataEnd`; valid duration units include `S`, `D`, `W`, `M`, and `Y`, while valid bar sizes include
`1 day`, `1 week`, and `1 month`. citeturn165670view0

## What you should swap later

- replace the Python baseline model with your trained model
- optionally move cache from JSON to parquet or Redis
- add retry / circuit breaker around IBKR and Python calls
- add finer bar sizes and volume profile logic
