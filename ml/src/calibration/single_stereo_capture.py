import os

import cv2

CAMERA_SOURCE = 0

SAVE_DIR_A = "stereo_images/deviceA"
SAVE_DIR_B = "stereo_images/deviceB"
os.makedirs(SAVE_DIR_A, exist_ok=True)
os.makedirs(SAVE_DIR_B, exist_ok=True)

cap = cv2.VideoCapture(CAMERA_SOURCE)
if not cap.isOpened():
    raise SystemExit("ERROR: could not open CAMERA_SOURCE.")

count = 0
waiting_for = "a"

print("=== SINGLE-PHONE STEREO CAPTURE (pipeline test only) ===")
print("This does NOT test real two-device intrinsics -- see Option 1 for that.")
print("Per pair:")
print("  1. Place phone at POSITION A, keep checkerboard fixed, press 'a'")
print("  2. Move phone to POSITION B (board unmoved), press 'b'")
print("  3. Move the BOARD to a new pose, repeat")
print("Press ESC when done (aim for 15-20 pairs).\n")

while True:
    ret, frame = cap.read()
    if not ret:
        break

    display = frame.copy()
    label = f"Next: Position {waiting_for.upper()}  |  Pairs captured: {count}"
    cv2.putText(display, label, (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
    cv2.imshow("Single-Phone Stereo Capture", display)

    key = cv2.waitKey(1) & 0xFF

    if key == 27:
        break

    elif key == ord("a") and waiting_for == "a":
        filename = os.path.join(SAVE_DIR_A, f"pair_{count:02d}.png")
        cv2.imwrite(filename, frame)
        print(f"Saved {filename} -- now move phone to Position B, press 'b'")
        waiting_for = "b"

    elif key == ord("b") and waiting_for == "b":
        filename = os.path.join(SAVE_DIR_B, f"pair_{count:02d}.png")
        cv2.imwrite(filename, frame)
        print(f"Saved {filename} -- pair {count} complete. Move board, then press 'a'")
        count += 1
        waiting_for = "a"

cap.release()
cv2.destroyAllWindows()
print(f"\nCaptured {count} complete pairs.")
