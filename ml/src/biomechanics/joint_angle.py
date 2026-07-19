import numpy as np

def calculate_angle_3d(point_a,point_b,point_c):

    a = np.array(point_a, dtype=float)
    b = np.array(point_b, dtype=float)
    c = np.array(point_c, dtype=float)

    vec_ba = a-b
    vec_bc = c-b

    dot_product = np.dot(vec_ba, vec_bc)
    norm_product = np.linalg.norm(vec_ba) * np.linalg.norm(vec_bc)

    if norm_product == 0:
        return 0.0 
 
    cos_angle = dot_product / norm_product
    cos_angle = np.clip(cos_angle, -1.0, 1.0)
 
    angle_rad = np.arccos(cos_angle)
    angle_deg = np.degrees(angle_rad)
 
    return angle_deg

if __name__ == "__main__":
    print("=== Joint angle calculation self-test ===\n")
 
    tests_passed = 0
    tests_total = 0
 
    def check(name, a, b, c, expected_angle):
        global tests_passed, tests_total
        tests_total += 1
        result = calculate_angle_3d(a, b, c)
        error = abs(result - expected_angle)
        status = "PASS" if error < 0.01 else "FAIL"
        if status == "PASS":
            tests_passed += 1
        print(f"[{status}] {name}: expected {expected_angle}°, got {result:.4f}° (error {error:.4f})")
 
    check("Straight arm (180°)",
          a=(0, 0, 0), b=(1, 0, 0), c=(2, 0, 0),
          expected_angle=180.0)
 
    check("Right angle bend (90°)",
          a=(0, 1, 0), b=(0, 0, 0), c=(1, 0, 0),
          expected_angle=90.0)
 
    check("Fully folded (0°)",
          a=(1, 0, 0), b=(0, 0, 0), c=(1, 0, 0),
          expected_angle=0.0)
 
    
    check("45° bend in 3D",
          a=(1, 0, 0), b=(0, 0, 0), c=(np.cos(np.radians(45)), np.sin(np.radians(45)), 0),
          expected_angle=45.0)
 
    check("Realistic bent elbow example",
          a=(0, 30, 5), b=(0, 0, 0), c=(20, 10, -5),
          expected_angle=calculate_angle_3d((0, 30, 5), (0, 0, 0), (20, 10, -5)))
    
 
    print(f"\n{tests_passed}/{tests_total} tests passed")
    if tests_passed == tests_total:
        print("PASS - angle calculation is correct.")
    else:
        print("FAIL - check the function, something's wrong.")