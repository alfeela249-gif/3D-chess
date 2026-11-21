package com.alfeela.chess3d

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal GLB 2.0 reader: extracts mesh POSITION/NORMAL/indices and node transforms. */
internal object GlbAsset {
    data class Primitive(val positions: FloatArray, val normals: FloatArray?, val indices: IntArray, val material: Int)
    data class Mesh(val name: String, val primitives: List<Primitive>)
    data class Node(val name: String, val mesh: Int, val translation: FloatArray, val rotation: FloatArray, val scale: FloatArray, val matrix: FloatArray?)
    data class Scene(val meshes: List<Mesh>, val nodes: List<Node>)
    private data class View(val offset: Int, val length: Int, val stride: Int)
    private data class Accessor(val view: Int, val offset: Int, val count: Int, val component: Int, val type: String)

    fun parse(bytes: ByteArray): Scene {
        require(bytes.size >= 20)
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(src.int == 0x46546C67) { "Invalid GLB magic" }
        require(src.int == 2) { "GLB 2.0 required" }
        val total = src.int
        require(total in 20..bytes.size)
        var json: JSONObject? = null
        var bin: ByteBuffer? = null
        while (src.position() + 8 <= total) {
            val length = src.int
            val kind = src.int
            val start = src.position()
            require(length >= 0 && start.toLong() + length <= total)
            when (kind) {
                0x4E4F534A -> {
                    val raw = ByteArray(length); src.get(raw)
                    json = JSONObject(String(raw, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n', '\r', '\t'))
                }
                0x004E4942 -> {
                    val part = src.slice().order(ByteOrder.LITTLE_ENDIAN)
                    part.limit(length); bin = part; src.position(start + length)
                }
                else -> src.position(start + length)
            }
        }
        val root = requireNotNull(json) { "Missing GLB JSON chunk" }
        val data = requireNotNull(bin) { "Missing GLB BIN chunk" }
        val vj = root.optJSONArray("bufferViews")
        val views = (0 until (vj?.length() ?: 0)).map {
            val v = vj!!.getJSONObject(it)
            View(v.optInt("byteOffset", 0), v.getInt("byteLength"), v.optInt("byteStride", 0))
        }
        val aj = root.optJSONArray("accessors")
        val accessors = (0 until (aj?.length() ?: 0)).map {
            val a = aj!!.getJSONObject(it)
            Accessor(a.optInt("bufferView", -1), a.optInt("byteOffset", 0), a.getInt("count"), a.getInt("componentType"), a.getString("type"))
        }
        fun floats(id: Int): FloatArray {
            val a = accessors[id]
            require(a.view >= 0 && a.component == 5126) { "Expected non-sparse FLOAT accessor" }
            val n = when (a.type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("Unsupported accessor type") }
            val v = views[a.view]; val stride = if (v.stride == 0) n * 4 else v.stride
            return FloatArray(a.count * n) { k ->
                val row = k / n; val col = k % n; val at = v.offset + a.offset + row * stride + col * 4
                require(at >= 0 && at.toLong() + 4 <= data.limit())
                data.getFloat(at)
            }
        }
        fun indices(id: Int): IntArray {
            val a = accessors[id]; require(a.view >= 0 && a.type == "SCALAR")
            val bytesPer = when (a.component) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Unsupported index type") }
            val v = views[a.view]; val stride = if (v.stride == 0) bytesPer else v.stride
            return IntArray(a.count) { i ->
                val at = v.offset + a.offset + i * stride
                require(at >= 0 && at.toLong() + bytesPer <= data.limit())
                when (a.component) { 5121 -> data.get(at).toInt() and 255; 5123 -> data.getShort(at).toInt() and 65535; else -> data.getInt(at) }
            }
        }
        val mj = root.optJSONArray("meshes")
        val meshes = (0 until (mj?.length() ?: 0)).map { mi ->
            val m = mj!!.getJSONObject(mi); val ps = m.getJSONArray("primitives")
            val primitives = (0 until ps.length()).map { pi ->
                val p = ps.getJSONObject(pi); val attrs = p.getJSONObject("attributes")
                val pos = floats(attrs.getInt("POSITION"))
                require(pos.size % 3 == 0)
                Primitive(pos, if (attrs.has("NORMAL")) floats(attrs.getInt("NORMAL")) else null,
                    if (p.has("indices")) indices(p.getInt("indices")) else IntArray(pos.size / 3) { it }, p.optInt("material", -1))
            }
            Mesh(m.optString("name", "Mesh $mi"), primitives)
        }
        val nj = root.optJSONArray("nodes")
        val nodes = (0 until (nj?.length() ?: 0)).mapNotNull { ni ->
            val n = nj!!.getJSONObject(ni)
            if (!n.has("mesh")) return@mapNotNull null
            fun vec(key: String, fallback: FloatArray) = if (n.has(key)) FloatArray(n.getJSONArray(key).length()) { n.getJSONArray(key).getDouble(it).toFloat() } else fallback
            val mat = if (n.has("matrix")) FloatArray(16) { n.getJSONArray("matrix").getDouble(it).toFloat() } else null
            Node(n.optString("name", "Node $ni"), n.getInt("mesh"),
                vec("translation", floatArrayOf(0f,0f,0f)), vec("rotation", floatArrayOf(0f,0f,0f,1f)),
                vec("scale", floatArrayOf(1f,1f,1f)), mat)
        }
        return Scene(meshes, nodes)
    }
}
