from pathlib import Path

import cv2

from src.calibration.charuco_calibrate import (
    MARKER_LENGTH_CM,
    SQUARE_LENGTH_CM,
    SQUARES_X,
    SQUARES_Y,
)

dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_5X5_100)
board = cv2.aruco.CharucoBoard(
    (SQUARES_X, SQUARES_Y), SQUARE_LENGTH_CM, MARKER_LENGTH_CM, dictionary
)
image = board.generateImage((1890, 2598), marginSize=120, borderBits=1)
output = Path("charuco_board.png")
cv2.imwrite(str(output), image)
print(output.resolve())
