import numpy as np
import tensorflow as tf
import os

print("--- EchoPath Acoustic Radar ML Trainer ---")
print("Generating physically modeled acoustic data...")
# Data features: [sidebandEnergy, delta]
# Classes: 0 = Clear, 1 = Hard Surface, 2 = Soft Object

num_samples = 5000

# 0: Clear (Low energy reflection, minimal change)
X_clear = np.random.normal(loc=[10.0, 1.0], scale=[4.0, 0.8], size=(num_samples, 2))
y_clear = np.zeros(num_samples)

# 1: Hard Surface (High energy reflection, sharp delta as user approaches)
# Glass/Concrete reflects >70% of 18.5kHz sound waves
X_hard = np.random.normal(loc=[85.0, 18.0], scale=[15.0, 5.0], size=(num_samples, 2))
y_hard = np.ones(num_samples)

# 2: Soft Object (Medium/Low energy reflection, muffled delta)
# Human body / foliage absorbs sound, leading to lower energy but still detectable proximity
X_soft = np.random.normal(loc=[38.0, 6.0], scale=[10.0, 2.5], size=(num_samples, 2))
y_soft = np.full(num_samples, 2)

# Combine and shuffle dataset
X = np.vstack([X_clear, X_hard, X_soft])
y = np.concatenate([y_clear, y_hard, y_soft])

indices = np.arange(len(X))
np.random.shuffle(indices)
X = X[indices]
y = y[indices]

print("\nBuilding lightweight Neural Network...")
model = tf.keras.Sequential([
    tf.keras.layers.Dense(16, activation='relu', input_shape=(2,)),
    tf.keras.layers.Dense(8, activation='relu'),
    tf.keras.layers.Dense(3, activation='softmax')
])

model.compile(optimizer='adam', loss='sparse_categorical_crossentropy', metrics=['accuracy'])

print("\nTraining model on acoustic signatures...")
model.fit(X, y, epochs=15, batch_size=32, validation_split=0.2)

print("\nConverting model to TensorFlow Lite (.tflite)...")
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

# Save directly into the Android project's assets folder
output_dir = r"d:\IIIT PS\new ps\EchoPathUI\app\src\main\assets"
os.makedirs(output_dir, exist_ok=True)
output_path = os.path.join(output_dir, "radar_model.tflite")

with open(output_path, "wb") as f:
    f.write(tflite_model)

print(f"\nSUCCESS! 🦇")
print(f"Real TFLite model trained and exported to: {output_path}")
print("The Android app will automatically use this ML model on the next launch.")
