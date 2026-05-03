import joblib
import pandas as pd
from pathlib import Path
from sklearn.ensemble import RandomForestClassifier


def main():
    feature_dir = Path("../../cache/features")
    if not feature_dir.exists():
        print("No feature cache found. Create cache/features/*.csv first.")
        return
    frames = []
    for file in feature_dir.glob("*.csv"):
        df = pd.read_csv(file)
        if "buy_label" in df.columns:
            frames.append(df)
    if not frames:
        print("No training files with buy_label found.")
        return
    df = pd.concat(frames, ignore_index=True)
    y = df["buy_label"]
    feature_cols = [c for c in df.columns if c not in {"buy_label", "symbol"}]
    X = df[feature_cols]
    model = RandomForestClassifier(n_estimators=200, random_state=42)
    model.fit(X, y)
    out = Path("artifacts")
    out.mkdir(exist_ok=True)
    joblib.dump({"model": model, "feature_cols": feature_cols}, out / "rf_model.joblib")
    print("Saved model to", out / "rf_model.joblib")


if __name__ == "__main__":
    main()
