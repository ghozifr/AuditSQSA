package com.suruhaja.ui.send

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.suruhaja.R
import com.suruhaja.databinding.FragmentSendFormBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SendFormFragment : Fragment() {

    private var _binding: FragmentSendFormBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SendViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSendFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle Edge-to-Edge insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply Top Insets (Status Bar) to Header
            binding.btnBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (20 * resources.displayMetrics.density).toInt()
            }
            binding.tvTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (24 * resources.displayMetrics.density).toInt()
            }

            // Apply Bottom Insets (Nav Bar + Keyboard) to Lanjut Button
            binding.btnNext.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // If keyboard is shown, use its height; otherwise use nav bar height.
                // +18dp keeps the neo-brutalism hard shadow clear of the system bar.
                val base = if (ime.bottom > 0) ime.bottom else navBars.bottom
                bottomMargin = base + (18 * resources.displayMetrics.density).toInt()
            }

            insets
        }

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        // Pre-fill dari draft (kalau balik dari halaman map)
        binding.etItemName.setText(viewModel.itemName)
        binding.etItemDesc.setText(viewModel.itemDesc)
        binding.etWeight.setText(if (viewModel.weightKg > 0) viewModel.weightKg.toString() else "")
        binding.etReceiverName.setText(viewModel.receiverName)
        binding.etReceiverPhone.setText(viewModel.receiverPhone)
        binding.etSenderName.setText(viewModel.senderName)
        binding.etSenderPhone.setText(viewModel.senderPhone)

        binding.btnNext.setOnClickListener {
            val itemName = binding.etItemName.text.toString().trim()
            val itemDesc = binding.etItemDesc.text.toString().trim()
            val weight = binding.etWeight.text.toString().trim().toIntOrNull() ?: 0
            val receiverName = binding.etReceiverName.text.toString().trim()
            val receiverPhone = binding.etReceiverPhone.text.toString().trim()
            val senderName = binding.etSenderName.text.toString().trim()
            val senderPhone = binding.etSenderPhone.text.toString().trim()

            val error = when {
                itemName.isEmpty() -> "Nama barang wajib diisi"
                weight < 1 -> "Berat minimal 1 kg"
                receiverName.isEmpty() -> "Nama penerima wajib diisi"
                receiverPhone.isEmpty() -> "No telepon penerima wajib diisi"
                senderName.isEmpty() -> "Nama pengirim wajib diisi"
                senderPhone.isEmpty() -> "No telepon pengirim wajib diisi"
                else -> null
            }
            if (error != null) {
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = error
                return@setOnClickListener
            }
            binding.tvError.visibility = View.GONE

            viewModel.itemName = itemName
            viewModel.itemDesc = itemDesc
            viewModel.weightKg = weight
            viewModel.receiverName = receiverName
            viewModel.receiverPhone = receiverPhone
            viewModel.senderName = senderName
            viewModel.senderPhone = senderPhone

            findNavController().navigate(R.id.sendLocationFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
