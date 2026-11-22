package ch.lauzhack.app.agent

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Unit-length vectors from an [Embedder]. [remember] keeps each text's vector in [known] (the training sentences, slides
 * and files), which the caller may save and pass back after a restart; [embed] is for what is said, which never repeats. */
class Embeddings(private val embedder: Embedder, val known: MutableMap<String, FloatArray> = mutableMapOf()) {
    suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { unit(embedder.embed(it)) }

    suspend fun remember(texts: List<String>): List<FloatArray> = texts.map { known.getOrPut(it) { unit(embedder.embed(it)) } }

    private fun unit(v: Vector): FloatArray {
        val a = FloatArray(v.values.size) { v.values[it].toFloat() }
        val n = sqrt(a.sumOf { it.toDouble() * it }).toFloat()
        for (i in a.indices) a[i] /= n
        return a
    }
}

/** What kind of request a sentence is: softmax regression over its embedding and its hashed words, trained on the
 * example sentences of each label with full-batch Adam. */
class Intents private constructor(val labels: List<String>, private val w: Array<FloatArray>, private val b: FloatArray) {
    /** Each label's probability, in [labels] order. */
    operator fun invoke(vec: FloatArray, text: String): Map<String, Double> {
        val g = grams(text)
        val z = FloatArray(labels.size) { k ->
            var s = 0.0
            for (j in vec.indices) s += vec[j].toDouble() * w[k][j]
            for (j in g.buckets) s += g.value.toDouble() * w[k][vec.size + j]
            s.toFloat() + b[k]
        }
        softmax(z)
        return labels.withIndex().associate { (k, label) -> label to z[k].toDouble() }
    }

    companion object {
        suspend fun train(embeddings: Embeddings, examples: List<Pair<String, List<String>>> = EXAMPLES): Intents {
            val labels = examples.map { it.first }
            require(labels.all { kindOf(it) != null }) { "unknown intent labels: ${labels.filter { kindOf(it) == null }}" }
            val texts = examples.flatMap { it.second }
            val y = examples.flatMapIndexed { k, (_, t) -> List(t.size) { k } }
            val fit = Fit(embeddings.remember(texts.map { CLASSIFY + it }), texts.map(::grams), y, labels.size)
            coroutineScope {
                for (t in 1..STEPS) {
                    labels.indices.map { k -> launch(Dispatchers.Default) { fit.forward(k) } }.joinAll()
                    fit.errors()
                    val c1 = (1 - 0.9.pow(t)).toFloat()
                    val c2 = (1 - 0.999.pow(t)).toFloat()
                    labels.indices.map { k -> launch(Dispatchers.Default) { fit.backward(k, c1, c2) } }.joinAll()
                }
            }
            return Intents(labels, fit.w, fit.b)
        }

        internal fun softmax(z: FloatArray) {
            val max = z.max()
            for (k in z.indices) z[k] = exp(z[k] - max)
            val sum = z.sum()
            for (k in z.indices) z[k] /= sum
        }
    }
}

/** Full-batch Adam, label by label. Every array is one row (a label's weights or logits, a sentence's embedding, a
 * feature over the sentences), so each inner loop is a multiply-add from index 0 that the JIT vectorizes, and the
 * labels train in parallel. */
private class Fit(private val x: List<FloatArray>, private val grams: List<Grams>, private val y: List<Int>, private val kk: Int) {
    private val n = x.size
    private val d = x[0].size
    private val xt = Array(d) { j -> FloatArray(n) { x[it][j] } }
    val w = Array(kk) { FloatArray(d + BUCKETS) }
    val b = FloatArray(kk)
    private val mw = Array(kk) { FloatArray(d + BUCKETS) }
    private val sw = Array(kk) { FloatArray(d + BUCKETS) }
    private val mb = FloatArray(kk)
    private val sb = FloatArray(kk)
    private val z = Array(kk) { FloatArray(n) } // the logits, then the errors (softmax - y) / n
    private val gw = Array(kk) { FloatArray(d + BUCKETS) }

    fun forward(k: Int) {
        val zk = z[k]
        val wk = w[k]
        zk.fill(0f)
        for (j in 0 until d) axpy(wk[j], xt[j], zk)
        for (i in 0 until n) {
            var s = zk[i]
            for (j in grams[i].buckets) s += grams[i].value * wk[d + j]
            zk[i] = s + b[k]
        }
    }

    fun errors() {
        val col = FloatArray(kk)
        for (i in 0 until n) {
            for (k in 0 until kk) col[k] = z[k][i]
            Intents.softmax(col)
            col[y[i]] -= 1f
            for (k in 0 until kk) z[k][i] = col[k] / n.toFloat()
        }
    }

    fun backward(k: Int, c1: Float, c2: Float) {
        val g = gw[k]
        g.fill(0f)
        var sum = 0f
        for (i in 0 until n) {
            val e = z[k][i]
            sum += e
            axpy(e, x[i], g)
            for (j in grams[i].buckets) g[d + j] += e * grams[i].value
        }
        val p = w[k]
        val m = mw[k]
        val s = sw[k]
        for (q in p.indices) {
            val grad = g[q] + L2 * p[q]
            m[q] += 0.1f * (grad - m[q])
            s[q] += 0.001f * (grad * grad - s[q])
            p[q] -= LR * m[q] / c1 / (sqrt(s[q] / c2) + 1e-8f)
        }
        mb[k] += 0.1f * (sum - mb[k])
        sb[k] += 0.001f * (sum * sum - sb[k])
        b[k] -= LR * mb[k] / c1 / (sqrt(sb[k] / c2) + 1e-8f)
    }

    /** y += a * x over x's length. */
    private fun axpy(a: Float, x: FloatArray, y: FloatArray) {
        for (i in x.indices) y[i] += a * x[i]
    }
}
