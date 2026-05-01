import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun main() {
    val HMAC_SECRET = "pocketnode-pro-secret-2024"
    val serial = "1234"
    
    val mac = Mac.getInstance("HmacSHA256")
    val key = SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
    mac.init(key)
    val hash = mac.doFinal(serial.toByteArray(Charsets.UTF_8))
    val hmacPart = hash.joinToString("") { "%02X".format(it) }.take(28)
    
    println("Valid Pro Key: PN-$hmacPart$serial")
}
