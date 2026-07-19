import time
from dataclasses import dataclass

import cv2
import numpy as np

from src.biomechanics.joint_angle import calculate_angle_3d
from src.biomechanics.one_euro_filter import OneEuroFilter3D
from src.biomechanics.rep_counting import RepCounter
from src.contracts import (
    AngleMetric,
    Joint3D,
    ProjectionCalibration,
    ReconstructionRequest,
    ReconstructionResult,
    ResultMetadata,
)
from src.triangulation.triangulation import build_projection_matrix, triangulate_multiview


@dataclass(frozen=True, slots=True)
class ExerciseDefinition:
    angle_name: str
    joint_ids: tuple[int, int, int]
    down_threshold: float
    up_threshold: float
    good_maximum: float | None = None


EXERCISES = {
    "squats": ExerciseDefinition("left_knee_flexion", (11, 13, 15), 155, 105, 115),
    "deadlifts": ExerciseDefinition("left_hip_hinge", (5, 11, 13), 155, 105, 120),
    "bench_press": ExerciseDefinition("left_elbow_flexion", (5, 7, 9), 145, 80, 100),
    "bicep_curls": ExerciseDefinition("left_elbow_flexion", (5, 7, 9), 145, 75, 90),
    "shoulder_press": ExerciseDefinition("left_elbow_extension", (5, 7, 9), 145, 80, 100),
}


class FormFusionPipeline:
    """Canonical composition of the existing triangulation and biomechanics modules."""

    def __init__(
        self,
        session_id: str,
        exercise: str,
        calibration: ProjectionCalibration,
        minimum_confidence: float,
    ) -> None:
        self.session_id = session_id
        self.exercise = exercise
        self.calibration = calibration
        self.minimum_confidence = minimum_confidence
        if exercise not in EXERCISES:
            raise ValueError(f"unsupported exercise: {exercise}")
        self.definition = EXERCISES[exercise]
        self.filters: dict[int, OneEuroFilter3D] = {}
        self.counter = RepCounter(
            down_threshold=self.definition.down_threshold,
            up_threshold=self.definition.up_threshold,
        )
        camera_a = np.asarray(calibration.camera_matrix_a, dtype=np.float64)
        camera_b = np.asarray(calibration.camera_matrix_b, dtype=np.float64)
        self.camera_matrices = {calibration.device_a: camera_a, calibration.device_b: camera_b}
        self.distortions = {
            calibration.device_a: np.asarray(calibration.distortion_a, dtype=np.float64),
            calibration.device_b: np.asarray(calibration.distortion_b, dtype=np.float64),
        }
        self.projections = {
            calibration.device_a: build_projection_matrix(camera_a, np.eye(3), np.zeros(3)),
            calibration.device_b: build_projection_matrix(
                camera_b,
                np.asarray(calibration.rotation_a_to_b, dtype=np.float64),
                np.asarray(calibration.translation_a_to_b, dtype=np.float64),
            ),
        }
        self.world_transform = np.asarray(calibration.world_transform, dtype=np.float64)

    def _observation_angle(self, point_maps: dict[str, dict[int, object]]) -> float | None:
        angles: list[float] = []
        first, vertex, third = self.definition.joint_ids
        for points in point_maps.values():
            if not all(joint in points for joint in (first, vertex, third)):
                continue
            a, b, c = points[first], points[vertex], points[third]
            if min(a.confidence, b.confidence, c.confidence) < self.minimum_confidence:
                continue
            angles.append(
                float(
                    calculate_angle_3d(
                        np.asarray([a.x, a.y, 0.0]),
                        np.asarray([b.x, b.y, 0.0]),
                        np.asarray([c.x, c.y, 0.0]),
                    )
                )
            )
        return float(np.median(angles)) if angles else None

    def process(self, request: ReconstructionRequest) -> ReconstructionResult:
        started = time.perf_counter()
        by_device = {observation.device_id: observation for observation in request.observations}
        calibrated_devices = [self.calibration.device_a, self.calibration.device_b]
        if not all(device in by_device for device in calibrated_devices):
            raise ValueError("synchronized observations do not match the calibrated device pair")
        point_maps = {
            device: {landmark.id: landmark for landmark in by_device[device].landmarks}
            for device in calibrated_devices
        }
        common_ids = set(point_maps[calibrated_devices[0]]) & set(point_maps[calibrated_devices[1]])
        timestamp_ms = max(by_device[device].captured_at_ms for device in calibrated_devices)
        timestamp_seconds = timestamp_ms / 1000.0
        vectors: dict[int, np.ndarray] = {}
        joints: dict[str, Joint3D] = {}

        for joint_id in sorted(common_ids):
            points: list[tuple[float, float]] = []
            projections: list[np.ndarray] = []
            confidences: list[float] = []
            for device in calibrated_devices:
                landmark = point_maps[device][joint_id]
                if landmark.confidence < self.minimum_confidence:
                    continue
                raw = np.asarray([[[landmark.x, landmark.y]]], dtype=np.float64)
                corrected = cv2.undistortPoints(
                    raw,
                    self.camera_matrices[device],
                    self.distortions[device],
                    P=self.camera_matrices[device],
                )[0, 0]
                points.append((float(corrected[0]), float(corrected[1])))
                projections.append(self.projections[device])
                confidences.append(landmark.confidence)
            if len(points) < 2:
                continue
            camera_point = triangulate_multiview(points, projections)
            homogeneous = self.world_transform @ np.append(camera_point, 1.0)
            world_point = homogeneous[:3] / homogeneous[3]
            smoothed = self.filters.setdefault(
                joint_id,
                OneEuroFilter3D(freq=30.0, min_cutoff=1.0, beta=0.1),
            ).filter(world_point, timestamp_seconds)
            vectors[joint_id] = smoothed
            joints[str(joint_id)] = Joint3D(
                x=float(smoothed[0]),
                y=float(smoothed[1]),
                z=float(smoothed[2]),
                confidence=float(np.prod(confidences) ** (1 / len(confidences))),
                observations=len(points),
            )

        primary_angle: float | None = None
        angles: list[AngleMetric] = []
        approximate = self.calibration.calibration_id.startswith("approximate-")
        if approximate:
            primary_angle = self._observation_angle(point_maps)
        elif all(joint in vectors for joint in self.definition.joint_ids):
            first, vertex, third = self.definition.joint_ids
            primary_angle = float(
                calculate_angle_3d(vectors[first], vectors[vertex], vectors[third])
            )
        if primary_angle is not None:
            angles.append(
                AngleMetric(
                    name=self.definition.angle_name,
                    degrees=primary_angle,
                    joint_ids=self.definition.joint_ids,
                )
            )
            rep_count, movement_state = self.counter.update(primary_angle)
        else:
            rep_count, movement_state = self.counter.rep_count, self.counter.state

        quality = "unknown"
        if primary_angle is not None:
            quality = (
                "good"
                if self.definition.good_maximum is None
                or primary_angle <= self.definition.good_maximum
                else "check"
            )
        timestamps = {device: by_device[device].captured_at_ms for device in calibrated_devices}
        return ReconstructionResult(
            session_id=request.session_id,
            captured_at_ms=timestamp_ms,
            joints_3d=joints,
            angles=angles,
            primary_angle_degrees=primary_angle,
            rep_count=rep_count,
            movement_state=movement_state,
            form_quality=quality,
            metadata=ResultMetadata(
                units=self.calibration.units,
                calibration_id=self.calibration.calibration_id,
                reprojection_error=self.calibration.reprojection_error,
                source_frame_ids={
                    device: by_device[device].frame_id for device in calibrated_devices
                },
                source_timestamps_ms=timestamps,
                pairing_delta_ms=max(timestamps.values()) - min(timestamps.values()),
                processing_time_ms=(time.perf_counter() - started) * 1000,
            ),
        )
