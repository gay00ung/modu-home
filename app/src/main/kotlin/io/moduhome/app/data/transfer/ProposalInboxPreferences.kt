package io.moduhome.app.data.transfer

import android.content.Context
import android.annotation.SuppressLint
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.core.transfer.ProposalInboxEntry
import io.moduhome.app.core.transfer.ProposalReviewStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Retains review decisions across process restarts; it does not deliver proposals to another device. */
internal class ProposalInboxPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("proposal_inbox", Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref", "UseKtx") // The relay acknowledges only an observed durable write.
    fun save(inbox: ErrandProposalInbox): Boolean = try {
        val entries = JSONArray()
        inbox.entries.forEach { entry ->
            entries.put(JSONObject()
                .put("link", ProposalLinkCodec.encode(entry.proposal))
                .put("created", entry.proposal.createdAtMillis)
                .put("status", entry.status.name))
        }
        preferences.edit().putString("inbox", JSONObject().put("schema", 1).put("entries", entries).toString()).commit()
    } catch (_: Exception) { false }

    fun load(nowMillis: Long): ProposalInboxLoadResult {
        require(nowMillis >= 0)
        return try {
            val raw = preferences.getString("inbox", null)
                ?: return ProposalInboxLoadResult.Loaded(ErrandProposalInbox.empty())
            if (raw.length > MAX_STORAGE_LENGTH) return invalidStoredInbox()
            val root = JSONObject(raw)
            if (root.getInt("schema") != 1) return invalidStoredInbox()
            val stored = root.getJSONArray("entries")
            if (stored.length() > ErrandProposalInbox.MAX_ENTRIES) return invalidStoredInbox()
            val entries = buildList {
                for (index in 0 until stored.length()) {
                    val item = stored.getJSONObject(index)
                    val created = item.getLong("created")
                    if (created < 0) return invalidStoredInbox()
                    // Decode at creation first so normal expiry can be pruned without hiding malformed data.
                    val decoded = ProposalLinkCodec.decode(item.getString("link"), created)
                    when (decoded) {
                        is ProposalDecodeResult.Invalid -> return invalidStoredInbox()
                        is ProposalDecodeResult.Valid -> {
                            if (decoded.proposal.createdAtMillis != created) return invalidStoredInbox()
                            val status = ProposalReviewStatus.valueOf(item.getString("status"))
                            if (decoded.proposal.unavailableReason(nowMillis) == null) {
                                add(ProposalInboxEntry(decoded.proposal, status))
                            }
                        }
                    }
                }
            }
            ProposalInboxLoadResult.Loaded(ErrandProposalInbox.restore(entries))
        } catch (_: JSONException) {
            invalidStoredInbox()
        } catch (_: IllegalArgumentException) {
            invalidStoredInbox()
        } catch (_: ClassCastException) {
            invalidStoredInbox()
        }
    }

    private fun invalidStoredInbox(): ProposalInboxLoadResult.Failed =
        ProposalInboxLoadResult.Failed("저장된 제안을 읽지 못했어요. 제안 링크를 다시 받아주세요")

    companion object {
        private const val MAX_STORAGE_LENGTH = (ProposalLinkCodec.MAX_LINK_LENGTH + 100) * ErrandProposalInbox.MAX_ENTRIES + 100
    }
}

internal sealed interface ProposalInboxLoadResult {
    data class Loaded(val inbox: ErrandProposalInbox) : ProposalInboxLoadResult
    data class Failed(val reason: String) : ProposalInboxLoadResult
}
