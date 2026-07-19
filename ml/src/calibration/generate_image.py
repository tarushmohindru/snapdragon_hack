import cv2
import os 

CAMERA_SOURCE = 0 #For phone paste the steaming URL

DEVICE_ID = "deviceA"

SAVE_DIR = f"calib_images_{DEVICE_ID}"
os.makedirs(SAVE_DIR,exist_ok=True)

cap = cv2.VideoCapture(CAMERA_SOURCE)

if not cap.isOpened():
    print("Unable to read the camera feed")

img_counter = 0

print(f"=== INTRINSICS CAPTURE: {DEVICE_ID} ===")
print("Move the checkerboard to different positions/angles/distances/tilts.")
print("Press 's' to save a frame, ESC when done (aim for 15-20 images).")

while True:
    ret, frame = cap.read()
    if not ret:
        break

    cv2.imshow(f'Calibration Capture - {DEVICE_ID}',frame)
    k = cv2.waitKey(1)
    # esc to close the camera window
    if k%256 == 27:
        print("Closing the Capture")
        break
    
    # s buttion to take pictures
    elif k%256 == ord('s'):
        img_name = os.path.join(SAVE_DIR,"frame_{}.png".format(img_counter))
        cv2.imwrite(img_name,frame)
        print('{} written!'.format(img_name))
        img_counter += 1
    
cap.release()
cv2.destroyAllWindows()
print(f"Captured {img_counter} images total for {DEVICE_ID}.")