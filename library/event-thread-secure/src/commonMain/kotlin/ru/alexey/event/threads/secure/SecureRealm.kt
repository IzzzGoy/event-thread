package ru.alexey.event.threads.secure

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import ru.alexey.event.threads.resources.ObservableResource
import kotlin.reflect.KClass

/**
 * One cryptographically-random 32-byte block, sourced from the platform's real CSPRNG via
 * `cryptography-core`'s JDK (Android)/Apple (iOS) providers - `AES.GCM` is used only as a
 * convenient, precisely-sized ([AES.Key.Size.B256] is exactly 32 bytes by definition) key
 * generator; the resulting bytes are never used for AES encryption itself, just as opaque random
 * material for [realmEncryptionKey].
 */
@OptIn(DelicateCryptographyApi::class)
private fun random32Bytes(): ByteArray {
    val generator = CryptographyProvider.Default.get(AES.GCM).keyGenerator(AES.Key.Size.B256)
    return generator.generateKeyBlocking().encodeToByteArrayBlocking(AES.Key.Format.RAW)
}

/** Realm's required encryption key length (64 bytes) isn't an AES key size - two independent,
 * CSPRNG-generated 32-byte blocks concatenated give 64 bytes of full entropy, equivalent to a
 * single 64-byte secure-random draw. */
private fun generateRealmEncryptionKey(): ByteArray = random32Bytes() + random32Bytes()

private const val REALM_KEY_STORE_PREFIX = "event-thread-secure.realm-key."

/**
 * Resolves the 64-byte encryption key Realm needs to open the database identified by [keyAlias] -
 * generated once via [generateRealmEncryptionKey] and persisted in the platform Keystore/Keychain-
 * backed [secureStore] under a name derived from [keyAlias], so the *same* key is returned (and
 * the encrypted file stays openable) on every later call for the same [keyAlias]. Never
 * regenerated once stored - Realm requires the exact same key every time it reopens a given
 * encrypted file, so a fresh random key each call would make the app permanently unable to read
 * its own previously-written data.
 *
 * Replaces the old `Seed`-based scheme, which derived the "key" from `kotlin.random.Random(seed)`
 * - a plain, non-cryptographic, fully deterministic PRNG. Given the same seed (a literal value
 * callers had to supply, typically hardcoded) it always produced the exact same 64 bytes, so the
 * "encryption" gave no real confidentiality at all: anyone who knew, guessed, or decompiled the
 * seed could reproduce the key and decrypt the database. There is no way to fix that scheme
 * without replacing the derivation mechanism entirely - a deterministic PRNG is not a KDF no
 * matter how the seed is chosen.
 */
private fun realmEncryptionKey(keyAlias: String): ByteArray {
    val store = secureStore()
    val storeKey = "$REALM_KEY_STORE_PREFIX$keyAlias"
    return store.data(storeKey) ?: generateRealmEncryptionKey().also { store.set(storeKey, it) }
}

/** Builds a [RealmConfiguration] for [objects], encrypted with a key generated once (via a real
 * CSPRNG) and persisted under [keyAlias] - see [realmEncryptionKey]. Use a stable, distinct
 * [keyAlias] per logical database (e.g. per user, or per named [secureDatabase]) so unrelated
 * databases don't collide on the same stored key. */
fun createConfig(
    vararg objects: KClass<out RealmObject>,
    keyAlias: String,
    config: RealmConfiguration.Builder.() -> Unit,
) = RealmConfiguration.Builder(objects.toSet())
    .apply(config)
    .encryptionKey(realmEncryptionKey(keyAlias))
    .build()


interface WrappedList<T> {
    val list: List<T>
}

fun <T> List<T>.wrap() = object : WrappedList<T> {
    override val list: List<T> = this@wrap
}

class SecureRealm<R : RealmObject, T : WrappedList<R>>(
    private val realm: Realm,
    private val clazz: KClass<R>,
    private val source: StateFlow<T>,
) : StateFlow<T> by source, ObservableResource<T> {
    override suspend fun update(block: (T) -> T) {
        realm.write {
            val old = query(clazz = clazz).find()
            val new = old.wrap().apply { block(this as T) }.list
            old.filter { it !in new }.forEach { delete(it) }
            new.filter { it !in old }.forEach { copyToRealm(it) }
        }
    }
}

inline fun <reified R : RealmObject, reified T : WrappedList<R>> secureDatabase(
    realm: Realm,
    scope: CoroutineScope
): ObservableResource<T> {
    val source: StateFlow<T> = realm.query(clazz = R::class).find().asFlow().map {
        it.list.wrap() as T
    }.stateIn(
        scope = scope,
        initialValue = emptyList<R>().wrap() as T,
        started = SharingStarted.Lazily
    )
    return SecureRealm<R, T>(
        realm,
        clazz = R::class,
        source = source
    )
}
