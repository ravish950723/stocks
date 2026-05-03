import pandas as pd

df = pd.read_csv("training/training_data.csv")

future_cols = [
    "90D Hit",
    "90D Gain (%)",
    "Days to Peak",
    "future_return",
    "label"
]

bad = [c for c in df.columns if c in future_cols]

print("LEAKAGE COLUMNS FOUND:", bad)

dup = df.duplicated(subset=["symbol", "date"]).sum()
print("DUPLICATES:", dup)