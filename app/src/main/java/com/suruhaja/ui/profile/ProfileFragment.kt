package com.suruhaja.ui.profile

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
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.suruhaja.R
import com.suruhaja.databinding.FragmentProfileBinding
import com.suruhaja.databinding.SheetEditProfileBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var auth: FirebaseAuth

    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.tvTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (20 * resources.displayMetrics.density).toInt()
            }

            binding.root.setPadding(0, 0, 0, navBars.bottom)
            insets
        }

        // Observe user data
        viewModel.user.observe(viewLifecycleOwner) { user ->
            val displayName = user.name.ifEmpty { "Pengguna" }
            binding.tvProfileName.text = displayName
            binding.tvProfileInitial.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            binding.tvProfilePhone.text = user.phone.ifEmpty { "Nomor belum ada" }
            binding.tvInfoPhone.text = user.phone.ifEmpty { "-" }
            binding.tvProfileBalance.text = "Rp %,d".format(user.balance)
            binding.tvProfilePoints.text = "%,d pts".format(user.points)
            val memberSince = formatMemberSince(user.createdAt)
            binding.tvMemberSince.text = memberSince
            binding.tvInfoJoined.text = memberSince.removePrefix("Member sejak ")

            // Email + status verifikasi (diverifikasi lewat kode OTP email).
            binding.tvInfoEmail.text = user.email.ifEmpty { "-" }
            val perluVerifikasi = user.email.isNotEmpty() && !user.emailVerified
            binding.tvEmailStatus.visibility = if (perluVerifikasi) View.VISIBLE else View.GONE
        }

        // Edit button → buka bottom sheet
        binding.btnEditProfile.setOnClickListener {
            showEditProfileSheet()
        }

        binding.cardProfileBalance.setOnClickListener {
            findNavController().navigate(R.id.topupFragment)
        }

        // Voucher Saya: daftar voucher + tukar kode.
        binding.btnMyVouchers.setOnClickListener {
            findNavController().navigate(R.id.voucherFragment)
        }

        // Sign out
        binding.btnSignOut.setOnClickListener {
            auth.signOut()
            findNavController().navigate(
                R.id.authFragment,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
            )
        }

        // Saklar tema gelap DIMATIKAN SEMENTARA (permintaan 2026-09-24).
        // Aplikasi customer selalu terang lewat ThemeManager.apply() di
        // SuruhajaApp (DARK_THEME_SUPPORTED = false). Baris tombolnya juga
        // disembunyikan di res/layout/fragment_profile.xml (btn_theme_toggle).
        // Wiring lama (switch_theme -> ThemeManager.setMode + recreate) dihapus
        // supaya tidak ada jalan masuk ke mode gelap.
    }

    private fun showEditProfileSheet() {
        val currentUser = viewModel.user.value ?: return

        val sheetBinding = SheetEditProfileBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetDialogTheme)

        // Pre-fill data
        sheetBinding.etEditName.setText(currentUser.name)
        sheetBinding.etEditPhone.setText(currentUser.phone)

        // Save
        sheetBinding.btnSaveProfile.setOnClickListener {
            val name = sheetBinding.etEditName.text.toString().trim()
            val phone = sheetBinding.etEditPhone.text.toString().trim()
            viewModel.saveProfile(name, phone)
        }

        // Observe save result
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ProfileViewModel.SaveResult.Success -> {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Profil disimpan", Toast.LENGTH_SHORT).show()
                }
                is ProfileViewModel.SaveResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                null -> {}
            }
        }

        // Cancel
        sheetBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun formatMemberSince(timestamp: Long): String {
        if (timestamp == 0L) return "Member sejak -"
        val date = Date(timestamp)
        val monthYear = SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("id-ID")).format(date)
        return "Member sejak $monthYear"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
