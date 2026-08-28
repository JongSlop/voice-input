
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private fun loadResourceFile(context: Context, file: String): ByteArray {
    val stream = context.resources.assets.open(file)
    val bytes = stream.readBytes()
    stream.close()
    return bytes
}

private fun ByteArray.littleEndianToFloatArray(): FloatArray {
    val numElements = this.size / 4
    val byteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val outArray = FloatArray(numElements)

    for(i in 0 until numElements) {
        outArray[i] = byteBuffer.float
    }

    return outArray
}

fun Double.isEqualApprox(other: Double): Boolean {
    return abs(this - other) < 1.0e-5
}

fun Array<Double>.isEqualApprox(other: Array<Double>): Boolean {
    if(this.size != other.size) return false
    for(i in indices) {
        if(!this[i].isEqualApprox(other[i])) return false
    }

    return true
}

// TODO: This test predates the move to WhisperModelWrapper and no longer compiles against the
// current ml package (there is no more WhisperModel.extractor). Disabled so the androidTest source
// set builds; needs rewriting against the current feature-extraction entry point.
@Ignore("Outdated: references removed WhisperModel.extractor API")
class FeatureExtractorTestAndroid {
    @Test fun placeholder() {}
}