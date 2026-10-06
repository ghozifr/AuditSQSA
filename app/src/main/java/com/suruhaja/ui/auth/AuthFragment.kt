package com.suruhaja.ui.auth

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.suruhaja.R
import com.suruhaja.databinding.FragmentAuthBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AuthFragment : Fragment() {

    private var _binding: FragmentAuthBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AuthViewModel by viewModels()

    private var isRegisterMode = true

    /** Hitungan mundur tombol "Kirim ulang kode" (server punya jeda 60 detik). */
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var resendDetik = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply status bar height to the Hero Header (first child of the root's
            // main LinearLayout: ScrollView → LinearLayout → [HeroHeader, CardArea]).
            (binding.root.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.updateLayoutParams<ViewGroup.LayoutParams> {
                height = (200 * resources.displayMetrics.density).toInt() + statusBars.top
            }

            binding.root.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        updateModeUi()
        setupPasswordToggle(binding.etPassword, binding.btnTogglePassword)
        setupPasswordToggle(binding.etConfirmPassword, binding.btnToggleConfirmPassword)

        // Tombol utama (Daftar / Masuk)
        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val confirmPassword = binding.etConfirmPassword.text.toString()
            hideError()
            if (isRegisterMode) {
                viewModel.register(email, password, confirmPassword, binding.etReferralCode.text.toString())
            } else {
                viewModel.login(email, password)
            }
        }

        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (!isRegisterMode && actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnSubmit.performClick()
                true
            } else false
        }

        binding.etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnSubmit.performClick()
                true
            } else false
        }

        binding.btnForgotPassword.setOnClickListener {
            hideError()
            viewModel.sendPasswordReset(binding.etEmail.text.toString().trim())
        }

        // Ganti mode Daftar <-> Masuk
        binding.btnToggleMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            updateModeUi()
            hideError()
        }

        // Tombol "Simpan Profil" setelah isi nama + nomor HP
        binding.btnSaveName.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val phone = binding.etPhone.text.toString().trim()
            hideError()
            viewModel.saveProfile(name, phone)
        }

        binding.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnSaveName.performClick()
                true
            } else false
        }

        // Verifikasi email: kirim ulang kode + cocokkan kode dari email
        binding.btnVerifyOtp.setOnClickListener {
            hideError()
            viewModel.verifyOtp(binding.etOtp.text.toString())
        }

        binding.etOtp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnVerifyOtp.performClick()
                true
            } else false
        }

        binding.btnResendOtp.setOnClickListener {
            if (resendDetik > 0) {
                Toast.makeText(
                    requireContext(),
                    "Tunggu $resendDetik detik sebelum minta kode lagi.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            hideError()
            viewModel.sendOtp()
        }

        // Verifikasi OTP WAJIB (keputusan pemilik): tidak ada tombol lewati.
        // Tombol "Nanti saja" (skip OTP) DIHAPUS — verifikasi wajib (keputusan pemilik).

        // Observe state
        // Pesan lembut soal kode referral (berhasil/gagal) — tidak pernah memblokir daftar.
        viewModel.referralNotice.observe(viewLifecycleOwner) { pesan ->
            if (!pesan.isNullOrBlank()) {
                Toast.makeText(requireContext(), pesan, Toast.LENGTH_LONG).show()
                viewModel.konsumsiReferralNotice()
            }
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthViewModel.AuthState.Idle -> showCredentialsInput()
                is AuthViewModel.AuthState.Authenticating -> showLoading()
                is AuthViewModel.AuthState.NewUser -> {
                    showNameInput()
                    binding.etName.requestFocus()
                }
                is AuthViewModel.AuthState.SavingName -> showLoading()
                is AuthViewModel.AuthState.Verified -> goToHome()
                is AuthViewModel.AuthState.ResetEmailSent -> {
                    showCredentialsInput()
                    Toast.makeText(
                        requireContext(),
                        "Jika email terdaftar, link reset password sudah dikirim.",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.resetState()
                }
                is AuthViewModel.AuthState.OtpRequired -> {
                    showOtpInput(state.email, state.devCode, state.notice)
                    startResendCountdown(state.cooldownSec)
                    // Peringatan (mis. kode gagal dikirim) tampil di bawah panel,
                    // jadi user tahu keadaannya; verifikasi tetap wajib diselesaikan.
                    state.notice?.let { if (it.isNotBlank()) showError(it) }
                }
                is AuthViewModel.AuthState.Error -> showError(state.message)
            }
        }
    }

    private fun updateModeUi() {
        binding.tvCardTitle.text = if (isRegisterMode) "Daftar" else "Masuk"
        binding.tvCardSubtitle.text =
            if (isRegisterMode) "Buat akun baru dengan email" else "Masuk dengan email kamu"
        binding.tvBtnSubmit.text = if (isRegisterMode) "Daftar" else "Masuk"
        binding.btnToggleMode.text =
            if (isRegisterMode) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar"
        binding.layoutConfirmPassword.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
        binding.layoutReferralCode.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
        binding.btnForgotPassword.visibility = if (isRegisterMode) View.GONE else View.VISIBLE
        if (!isRegisterMode) binding.etReferralCode.text?.clear()
        binding.etPassword.imeOptions =
            if (isRegisterMode) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        if (!isRegisterMode) binding.etConfirmPassword.text?.clear()
    }

    private fun setupPasswordToggle(input: EditText, button: ImageButton) {
        button.setOnClickListener {
            val selection = input.selectionStart.coerceAtLeast(0)
            val isHidden = input.transformationMethod is PasswordTransformationMethod
            input.transformationMethod = if (isHidden) null else PasswordTransformationMethod.getInstance()
            input.setSelection(selection.coerceAtMost(input.text?.length ?: 0))
            button.contentDescription =
                if (isHidden) "Sembunyikan password" else "Tampilkan password"
        }
    }

    private fun goToHome() {
        findNavController().navigate(
            R.id.homeFragment,
            null,
            androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.nav_graph, true)
                .build()
        )
    }

    private fun showCredentialsInput() {
        binding.layoutCredentialsInput.visibility = View.VISIBLE
        binding.layoutNameInput.visibility = View.GONE
        binding.layoutOtpInput.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun showNameInput() {
        binding.layoutCredentialsInput.visibility = View.GONE
        binding.layoutNameInput.visibility = View.VISIBLE
        binding.layoutOtpInput.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        hideError()
    }

    /** Layar verifikasi email: tunjukkan tujuan email (sudah disamarkan server). */
    private fun showOtpInput(maskedEmail: String, devCode: String?, notice: String? = null) {
        binding.layoutCredentialsInput.visibility = View.GONE
        binding.layoutNameInput.visibility = View.GONE
        binding.layoutOtpInput.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.etOtp.text?.clear()

        binding.tvOtpSubtitle.text = when {
            maskedEmail.isNotBlank() -> "Kode 6 digit sudah dikirim ke $maskedEmail"
            notice != null -> "Kode belum terkirim. Periksa koneksi internet lalu tekan \"Kirim ulang kode\"."
            else -> "Kode 6 digit sudah dikirim ke email kamu"
        }

        // Hanya muncul kalau server dijalankan dengan OTP_DEV_MODE (pengujian).
        if (devCode.isNullOrBlank()) {
            binding.tvOtpDevCode.visibility = View.GONE
        } else {
            binding.tvOtpDevCode.visibility = View.VISIBLE
            binding.tvOtpDevCode.text = "Mode uji: kode = $devCode"
        }

        binding.etOtp.requestFocus()
        hideError()
    }

    /** Hitung mundur tombol kirim ulang; server menolak permintaan terlalu cepat. */
    private fun startResendCountdown(seconds: Int) {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        resendDetik = seconds.coerceAtLeast(0)

        val tick = object : Runnable {
            override fun run() {
                if (_binding == null) return
                if (resendDetik <= 0) {
                    binding.btnResendOtp.text = com.suruhaja.data.policy.OtpInputPolicy.resendLabel(0)
                    binding.btnResendOtp.alpha = 1f
                    return
                }
                binding.btnResendOtp.text = com.suruhaja.data.policy.OtpInputPolicy.resendLabel(resendDetik)
                binding.btnResendOtp.alpha = 0.5f
                resendDetik -= 1
                countdownHandler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = tick
        countdownHandler.post(tick)
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = null
        _binding = null
    }
}
