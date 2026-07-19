import numpy as np
import cv2 
import glob

CHECKERBOARD = (9,6) # number of inner corners
SQUARE_SIZE = 2.5 # size of each corner
IMAGES_PATH = "calib_images/*.png"

DEVICE_ID = "deviceA"
IMAGES_PATH = f"calib_images_{DEVICE_ID}/*.png"

#object points
objp = np.zeros((CHECKERBOARD[0]*CHECKERBOARD[1],3), np.float32)
objp[:,:2] = np.mgrid[0:CHECKERBOARD[0],0:CHECKERBOARD[1]].T.reshape(-1,2)
objp *= SQUARE_SIZE

# Arrays to store object points and image points from all the images.
objpoints = [] # 3d point in real world space
imgpoints = [] # 2d points in image plane.

images = glob.glob(IMAGES_PATH)
if not images:
    raise SystemExit(f"No images found at '{IMAGES_PATH}'. "
                      f"Check DEVICE_ID and that generate_image.py was run for this device.")

gray_shape = None

for fname in images:
    img = cv2.imread(fname)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray_shape = gray.shape[::-1]

    ret, corners = cv2.findChessboardCorners(gray,CHECKERBOARD,None)

    if ret:

        criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)
        corners2 = cv2.cornerSubPix(gray, corners, (11, 11), (-1, -1), criteria)
        
        objpoints.append(objp)
        imgpoints.append(corners2)

        # Corner Visualisation
        cv2.drawChessboardCorners(img, CHECKERBOARD, corners2, ret)
        cv2.imshow('Corners', img)
        cv2.waitKey(1000)
    else:
        print(f"Chessboard NOT found in {fname}")

cv2.destroyAllWindows()

# Run Calibration

if len(objpoints) < 8:
    print(f"WARNING: only {len(objpoints)} usable images — intrinsics may be unstable. Aim for 15-20.")

ret, camera_matrix, dist_coeffs, rvecs, tvecs = cv2.calibrateCamera(
    objpoints, imgpoints, gray_shape, None, None,
    flags=cv2.CALIB_FIX_K3
    )

print("\n=== CALIBRATION RESULTS ===")
print("Camera matrix (intrinsics):\n", camera_matrix)
print("\nDistortion coefficients:\n", dist_coeffs)
print(f"\nReprojection error (want < 1.0, ideally < 0.5): {ret:.4f}")

# Save results for later use in triangulation
out_name = f"calib_{DEVICE_ID}.npz"
np.savez(out_name,
         camera_matrix=camera_matrix,
         dist_coeffs=dist_coeffs,
         reprojection_error=ret)

print(f"\nSaved to {out_name}")
