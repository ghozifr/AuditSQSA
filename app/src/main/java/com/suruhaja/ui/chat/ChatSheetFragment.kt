package com.suruhaja.ui.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.suruhaja.R

/**
 * Chat 1-1 customer ↔ driver untuk satu order (ride/send/food).
 * Pesan disimpan di subcollection `{collection}/{orderId}/messages`.
 */
class ChatSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_COLLECTION = "collection"
        private const val ARG_ORDER_ID = "orderId"
        private const val ARG_MY_ROLE = "myRole"
        private const val ARG_PEER_NAME = "peerName"
        private const val ARG_SERVICE = "service"

        /** Jumlah pesan terakhir yang dipantau (hemat baca Firestore). */
        private const val CHAT_MESSAGE_LIMIT = 50L

        fun newInstance(collection: String, orderId: String, myRole: String, peerName: String, service: String = "") =
            ChatSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_COLLECTION, collection)
                    putString(ARG_ORDER_ID, orderId)
                    putString(ARG_MY_ROLE, myRole)
                    putString(ARG_PEER_NAME, peerName)
                    putString(ARG_SERVICE, service)
                }
            }
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val myUid get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private var listener: ListenerRegistration? = null

    private val collectionName get() = arguments?.getString(ARG_COLLECTION) ?: "orders"
    private val orderId get() = arguments?.getString(ARG_ORDER_ID) ?: ""
    private val myRole get() = arguments?.getString(ARG_MY_ROLE) ?: "customer"
    private val peerName get() = arguments?.getString(ARG_PEER_NAME) ?: ""
    private val service get() = arguments?.getString(ARG_SERVICE) ?: ""

    private var messagesContainer: LinearLayout? = null
    private var scroll: ScrollView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_chat_sheet, container, false)
        view.findViewById<TextView>(R.id.chat_title).text = "Chat $peerName"
        messagesContainer = view.findViewById(R.id.chat_messages)
        scroll = view.findViewById(R.id.chat_scroll)
        val input = view.findViewById<EditText>(R.id.chat_input)
        val send = view.findViewById<View>(R.id.btn_send)

        listener = firestore.collection(collectionName).document(orderId).collection("messages")
            .orderBy("sentAt", Query.Direction.ASCENDING)
            // Hemat baca: hanya pantau 50 pesan terakhir, bukan seluruh riwayat chat.
            .limitToLast(CHAT_MESSAGE_LIMIT)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                if (snap.metadata.isFromCache) return@addSnapshotListener // server-only, realtime
                rebuild(snap.documents.map {
                    (it.getString("text") ?: "") to (it.getString("senderId") ?: "")
                })
            }

        // Reset unread counter milik saya ketika chat dibuka.
        val myUnreadField = if (myRole == "driver") "unreadByDriver" else "unreadByCustomer"
        firestore.collection(collectionName).document(orderId)
            .update(myUnreadField, 0)
            .addOnFailureListener { }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty() || myUid.isEmpty()) return@setOnClickListener
            input.text.clear()
            firestore.collection(collectionName).document(orderId).collection("messages")
                .add(
                    mapOf(
                        "senderId" to myUid,
                        "senderRole" to myRole,
                        "text" to text,
                        "sentAt" to FieldValue.serverTimestamp()
                    )
                )
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Gagal kirim: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            // Increment unread counter pihak penerima di dokumen order.
            val unreadField = if (myRole == "driver") "unreadByCustomer" else "unreadByDriver"
            firestore.collection(collectionName).document(orderId)
                .update(unreadField, FieldValue.increment(1))
                .addOnFailureListener { }
        }
        return view
    }

    private fun rebuild(pairs: List<Pair<String, String>>) {
        val container = messagesContainer ?: return
        container.removeAllViews()
        if (pairs.isEmpty()) {
            val hint = TextView(requireContext()).apply {
                text = "Belum ada pesan. Mulai percakapan!"
                textSize = 13f
                typeface = resources.getFont(R.font.poppins_bold)
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
            }
            container.addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }
        val accent = when {
            collectionName == "merchant_orders" -> 0xFFFB923C.toInt()   // SuruhFood = orange
            service == "send" -> 0xFF3EC76E.toInt()                     // SuruhSend = hijau
            else -> 0xFF5EEAD4.toInt()                                  // SuruhRide = teal
        }
        val mineBg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(accent); setStroke(dp(2), Color.BLACK)
        }
        val theirsBg = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(0xFFFFFFFF.toInt()); setStroke(dp(2), Color.BLACK)
        }
        for ((text, senderId) in pairs) {
            val mine = senderId == myUid
            val tv = TextView(requireContext()).apply {
                this.text = text
                textSize = 13f
                typeface = resources.getFont(R.font.poppins_bold)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = if (mine) mineBg else theirsBg
                setTextColor(Color.parseColor("#202020"))
                maxWidth = dp(260)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = if (mine) Gravity.END else Gravity.START
            lp.topMargin = dp(4)
            lp.bottomMargin = dp(4)
            container.addView(tv, lp)
        }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        listener?.remove()
        listener = null
        messagesContainer = null
        scroll = null
    }
}
