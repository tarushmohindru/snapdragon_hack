package com.yourbusiness.formfusion.pose

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PoseStubsTest {

    @Test
    fun `StubPersonDetector initializes successfully and always returns an empty list`() {
        val stub = StubPersonDetector()
        assertTrue(stub.initialize())
        assertTrue(stub.detect(mock(Bitmap::class.java)).isEmpty())
        stub.release() // must not throw
    }

    @Test
    fun `StubPoseEstimator initializes successfully and always returns an empty list`() {
        val stub = StubPoseEstimator()
        assertTrue(stub.initialize())
        val box = BoundingBox(0f, 0f, 10f, 10f, 0.5f)
        assertTrue(stub.estimate(mock(Bitmap::class.java), box).isEmpty())
        stub.release() // must not throw
    }
}
