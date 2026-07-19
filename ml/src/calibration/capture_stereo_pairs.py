import cv2
import os

CAMERA_SOURCE_A = 0
CAMERA_SOURCE_B = 1

SAVE_DIR_A = "stereo_images/deviceA"
SAVE_DIR_B = "stereo_images/deviceB"

os.makedirs(SAVE_DIR_A, exist_ok=True)
os.makedirs(SAVE_DIR_B, exist_ok=True)

capA = cv2.VideoCapture(CAMERA_SOURCE_A)
capB = cv2.VideoCapture(CAMERA_SOURCE_B)

if not capA.isOpened() or not capB.isOpened():
    raise SystemExit("ERROR: could not open one or both camera sources. "
                      "Check CAMERA_SOURCE_A / CAMERA_SOURCE_B.")

count = 0

print("=== SIMULTANEOUS STEREO PAIR CAPTURE (2 devices) ===")
print("Both devices must be looking at the SAME checkerboard pose when you save.")
print("Keep the board still for a moment before pressing 's' -- the two reads")
print("aren't hardware-synced, so a static board avoids any timing skew.")
print("Move the BOARD (not the cameras) between pairs. Press ESC when done.")
print("Aim for 15-20 pairs covering different positions/angles/distances/tilts.\n")

#waiting_for = "a"

while True:

    retA, frameA = capA.read()
    retB, frameB = capB.read()
    if not retA or not retB:
        print("WARNING: dropped a frame from one camera, retrying...")
        continue
    
    dispA = frameA.copy()
    dispB = frameB.copy()

    label = f"Pairs captured: {count}  |  's' to save, ESC to quit"

    cv2.putText(dispA, f"Device A | {label}", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
    cv2.putText(dispB, f"Device B | {label}", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
    cv2.imshow("Device A", dispA)
    cv2.imshow("Device B", dispB)
 
    key = cv2.waitKey(1) & 0xFF

    if key == 27: 
        break

    elif key == ord('s'):
        fnameA = os.path.join(SAVE_DIR_A, f"pair_{count:02d}.png")
        fnameB = os.path.join(SAVE_DIR_B, f"pair_{count:02d}.png")
        cv2.imwrite(fnameA, frameA)
        cv2.imwrite(fnameB, frameB)
        print(f"Saved pair {count}: {fnameA}  &  {fnameB}  -- now move the board and press 's' again")
        count += 1

capA.release()
capB.release()

cv2.destroyAllWindows()
print(f"\nCaptured {count} synchronized pairs.")