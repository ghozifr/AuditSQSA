package com.suruhaja.ui.topup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import com.suruhaja.R
import com.suruhaja.databinding.FragmentTopupBinding
import com.suruhaja.util.QrisUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class TopupFragment : Fragment() {

    private var _binding: FragmentTopupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TopupViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTopupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Header LinearLayout is the first child
            (binding.root as? ViewGroup)?.getChildAt(0)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
            }

            // Apply bottom padding to main content ScrollView or layout
            // Account for Nav bar and Keyboard
            binding.root.setPadding(0, 0, 0, if (ime.bottom > 0) ime.bottom else navBars.bottom)
            insets
        }

        viewModel.balance.observe(viewLifecycleOwner) { bal ->
            binding.tvBalance.text =
                if (bal != null) "Saldo: Rp %,d".format(bal) else "Saldo: —"
        }

        viewModel.qrContent.observe(viewLifecycleOwner) { content ->
            if (content != null) {
                binding.qrArea.visibility = View.VISIBLE
                renderQr(content)
            }
        }

        viewModel.payAmount.observe(viewLifecycleOwner) { amt ->
            if (amt > 0) binding.tvPayAmount.text = "Bayar tepat Rp %,d".format(amt)
        }

        viewModel.message.observe(viewLifecycleOwner) { msg ->
            binding.tvStatus.text = msg
        }

        viewModel.countdown.observe(viewLifecycleOwner) { c ->
            binding.tvCountdown.text = c
        }

        viewModel.status.observe(viewLifecycleOwner) { status ->
            val enabled = status == TopupViewModel.STATUS_IDLE ||
                status == TopupViewModel.STATUS_EXPIRED ||
                status == TopupViewModel.STATUS_ERROR
            binding.btnGenerate.isEnabled = enabled
            binding.btnGenerate.text = when (status) {
                TopupViewModel.STATUS_PROCESSING -> "MEMBUAT QR..."
                TopupViewModel.STATUS_PENDING -> "Menunggu pembayaran..."
                TopupViewModel.STATUS_CONFIRMED -> "Top-up berhasil"
                TopupViewModel.STATUS_EXPIRED -> "Buat QR Lagi"
                else -> "Buat QR Bayar"
            }
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when (status) {
                        TopupViewModel.STATUS_CONFIRMED -> R.color.brand
                        TopupViewModel.STATUS_EXPIRED, TopupViewModel.STATUS_ERROR -> R.color.red_accent
                        else -> R.color.text_muted
                    }
                )
            )
        }

        val prefillAmount = arguments?.getLong("amount", 0L) ?: 0L
        if (prefillAmount > 0) {
            binding.etAmount.setText(prefillAmount.toString())
            viewModel.onAmountChanged(prefillAmount.toString())
        }

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnGenerate.setOnClickListener {
            viewModel.onAmountChanged(binding.etAmount.text.toString())
            viewModel.generateQr()
        }
    }

    private fun renderQr(content: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                QrisUtil.generateQrBitmap(content, 800)
            }
            binding.ivQr.setImageBitmap(bmp)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
