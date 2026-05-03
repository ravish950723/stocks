@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo   TRADING ML PIPELINE - TRAIN + START + HEALTH CHECK
echo   Uses Java-exported training/raw_feature_history.csv
echo ============================================================

cd /d C:\ravish\py_work\IKBR\predic\trading-platform\python\model_service

set LOG_DIR=logs
if not exist %LOG_DIR% mkdir %LOG_DIR%

set LOG_FILE=%LOG_DIR%\full_pipeline.log
set SERVICE_LOG=%LOG_DIR%\ml_service.log
set HEALTH_LOG=%LOG_DIR%\health_check.json

echo [%date% %time%] ML_PIPELINE_START > %LOG_FILE%
echo Working directory: %cd% >> %LOG_FILE%

echo.
echo [STEP 1] Python version
python --version
python --version >> %LOG_FILE% 2>&1

echo.
echo [STEP 2] Installing/checking requirements
pip install -r requirements.txt >> %LOG_FILE% 2>&1
if errorlevel 1 (
    echo ERROR: requirements install failed
    powershell -Command "Get-Content '%LOG_FILE%' -Tail 80"
    exit /b 1
)

echo.
echo [STEP 3] Cleaning old unsafe model artifacts
if not exist models mkdir models

del /q models\dl_model.joblib 2>nul
del /q models\dl_metrics.json 2>nul
del /q models\ensemble_model.joblib 2>nul
del /q models\ensemble_metrics.json 2>nul
del /q models\xgb_model.joblib 2>nul
del /q models\xgb_metrics.json 2>nul

echo Preserving strong_buy_model if present >> %LOG_FILE%

echo.
echo [STEP 4] Verify Java-exported raw_feature_history.csv

if not exist training\raw_feature_history.csv (
    echo ERROR: Java-exported training\raw_feature_history.csv missing
    echo Run Java pipeline first so TrainingFeatureExportService creates:
    echo C:\ravish\py_work\IKBR\predic\trading-platform\python\model_service\training\raw_feature_history.csv
    exit /b 1
)

echo OK: Java-exported training\raw_feature_history.csv found
echo [%date% %time%] JAVA_FEATURE_FILE_FOUND >> %LOG_FILE%

echo.
echo [STEP 5] Build training data
python -m app.build_training_data >> %LOG_FILE% 2>&1
if errorlevel 1 (
    echo ERROR: build_training_data failed
    powershell -Command "Get-Content '%LOG_FILE%' -Tail 120"
    exit /b 1
)

echo.
echo [STEP 6] Train XGBoost model
python -m app.train_xgb >> %LOG_FILE% 2>&1
if errorlevel 1 (
    echo ERROR: train_xgb failed
    powershell -Command "Get-Content '%LOG_FILE%' -Tail 120"
    exit /b 1
)

echo.
echo [STEP 7] Verify model files

if not exist models\xgb_model.joblib (
    echo ERROR: xgb_model.joblib missing
    exit /b 1
)

if not exist models\xgb_metrics.json (
    echo ERROR: xgb_metrics.json missing
    exit /b 1
)

echo OK: XGB model files created

echo.
echo [STEP 8] Model folder snapshot
dir models
dir models >> %LOG_FILE% 2>&1

echo.
echo [STEP 9] Kill existing service on port 8000 if running
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8000 ^| findstr LISTENING') do (
    echo Killing PID %%a
    taskkill /PID %%a /F >nul 2>&1
)

timeout /t 2 >nul

echo.
echo [STEP 10] Starting ML service using ml_service_app.py
echo [%date% %time%] ML_SERVICE_START >> %LOG_FILE%

start "Trading ML Service" cmd /k "cd /d C:\ravish\py_work\IKBR\predic\trading-platform\python\model_service && python -m uvicorn ml_service_app:app --host 127.0.0.1 --port 8000 > logs\ml_service.log 2>&1"

timeout /t 8 >nul

echo.
echo [STEP 11] Running health check

curl -s http://127.0.0.1:8000/health > %HEALTH_LOG%

findstr /i "\"status\"" %HEALTH_LOG% >nul
if errorlevel 1 (
    echo ERROR: Health check failed - no status field
    echo.
    echo Service log:
    powershell -Command "Get-Content '%SERVICE_LOG%' -Tail 80"
    echo.
    echo Health output:
    type %HEALTH_LOG%
    exit /b 1
)

findstr /i "\"ok\"" %HEALTH_LOG% >nul
if errorlevel 1 (
    echo ERROR: Health check failed - status not ok
    echo.
    echo Service log:
    powershell -Command "Get-Content '%SERVICE_LOG%' -Tail 80"
    echo.
    echo Health output:
    type %HEALTH_LOG%
    exit /b 1
)

echo HEALTH CHECK PASSED
type %HEALTH_LOG%

echo.
echo ============================================================
echo   ML PIPELINE COMPLETED SUCCESSFULLY
echo ============================================================
echo Main Log    : %LOG_FILE%
echo Service Log : %SERVICE_LOG%
echo Health Log  : %HEALTH_LOG%
echo Service URL : http://127.0.0.1:8000
echo Java URL    : http://127.0.0.1:8000/predict
echo ============================================================

echo [%date% %time%] ML_PIPELINE_SUCCESS >> %LOG_FILE%

endlocal
exit /b 0