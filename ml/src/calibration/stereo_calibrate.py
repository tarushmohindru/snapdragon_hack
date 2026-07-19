import cv2
import numpy as np
import glob

CHECKERBOARD = (9, 6)
SQUARE_SIZE = 2.5   # use the SAME value you measured earlier

DEVICE_A_ID = "deviceA"
DEVICE_B_ID = "deviceB"

PATH_A = f"stereo_images/{DEVICE_A_ID}/*.png"
PATH_B = f"stereo_images/{DEVICE_B_ID}/*.png"

# Load existing intrinsics (already calibrated)
calib_A = np.load(f"calib_{DEVICE_A_ID}.npz")
calib_B = np.load(f"calib_{DEVICE_B_ID}.npz")
mtx1, dist1 = calib_A["camera_matrix"], calib_A["dist_coeffs"]
mtx2, dist2 = calib_B["camera_matrix"], calib_B["dist_coeffs"]

#object points
objp = np.zeros((CHECKERBOARD[0]*CHECKERBOARD[1], 3), np.float32)
objp[:, :2] = np.mgrid[0:CHECKERBOARD[0], 0:CHECKERBOARD[1]].T.reshape(-1, 2)
objp *= SQUARE_SIZE

objpoints = []
imgpoints_A = []
imgpoints_B = []

images_A = sorted(glob.glob(PATH_A))
images_B = sorted(glob.glob(PATH_B))

if not images_A or not images_B:
    raise SystemExit(f"No images found (A: {len(images_A)}, B: {len(images_B)}). "
                      f"Check PATH_A/PATH_B and that capture_dual_stereo.py was run.")
 
if len(images_A) != len(images_B):
    raise SystemExit(f"Mismatched pair counts: {len(images_A)} in A vs {len(images_B)} in B. "
                      f"Every pair needs both an A and a B frame -- re-check the capture folders.")

criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)
img_shape = None

for fA,fB in zip(images_A,images_B):

    imgA = cv2.imread(fA)
    imgB = cv2.imread(fB)

    grayA = cv2.cvtColor(imgA, cv2.COLOR_BGR2GRAY)
    grayB = cv2.cvtColor(imgB, cv2.COLOR_BGR2GRAY)
    img_shape = grayA.shape[::-1]

    retA, cornersA = cv2.findChessboardCorners(grayA, CHECKERBOARD, None)
    retB, cornersB = cv2.findChessboardCorners(grayB, CHECKERBOARD, None)


    if retA and retB:
        cornersA = cv2.cornerSubPix(grayA, cornersA, (11, 11), (-1, -1), criteria)
        cornersB = cv2.cornerSubPix(grayB, cornersB, (11, 11), (-1, -1), criteria)

        objpoints.append(objp)

        imgpoints_A.append(cornersA)
        imgpoints_B.append(cornersB)
    else:
        print(f"Pair skipped (chessboard not found in one or both): {fA}, {fB}")

print(f"\nUsing {len(objpoints)} valid pairs for stereo calibration")

flags = cv2.CALIB_FIX_INTRINSIC

ret, mtx1, dist1, mtx2, dist2, R, T, E, F = cv2.stereoCalibrate(
    objpoints, imgpoints_A, imgpoints_B,
    mtx1, dist1, mtx2, dist2,
    img_shape, criteria=criteria, flags=flags
)

print("\n=== STEREO CALIBRATION RESULTS ===")
print(f"Device pair: {DEVICE_A_ID} -> {DEVICE_B_ID}")
print(f"Stereo reprojection error: {ret:.4f}")
print("\nRotation matrix (A -> B):\n", R)
print("\nTranslation vector (A -> B), in same units as SQUARE_SIZE:\n", T)

# Save for triangulation step
out_name = f"stereo_calibration_{DEVICE_A_ID}_{DEVICE_B_ID}.npz"
np.savez(out_name,
         mtx1=mtx1, dist1=dist1,
         mtx2=mtx2, dist2=dist2,
         R=R, T=T)
 
print(f"\nSaved to {out_name}")