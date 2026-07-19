import asyncio

from src.contracts import ProjectionCalibration
from src.pipeline.formfusion_pipeline import FormFusionPipeline


class PipelineRegistry:
    def __init__(self, minimum_confidence: float) -> None:
        self.minimum_confidence = minimum_confidence
        self._pipelines: dict[str, FormFusionPipeline] = {}
        self._lock = asyncio.Lock()

    async def get(
        self,
        session_id: str,
        exercise: str,
        calibration: ProjectionCalibration,
    ) -> FormFusionPipeline:
        async with self._lock:
            current = self._pipelines.get(session_id)
            if (
                current is None
                or current.exercise != exercise
                or current.calibration.calibration_id != calibration.calibration_id
            ):
                current = FormFusionPipeline(
                    session_id,
                    exercise,
                    calibration,
                    self.minimum_confidence,
                )
                self._pipelines[session_id] = current
            return current

    async def delete(self, session_id: str) -> None:
        async with self._lock:
            self._pipelines.pop(session_id, None)
