package com.suruhaja.ui.send

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.suruhaja.R
import com.suruhaja.databinding.FragmentSendVehicleBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SendVehicleFragment : Fragment() {

    private var _binding: FragmentSendVehicleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SendViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSendVehicleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            
            binding.btnBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (20 * resources.displayMetrics.density).toInt()
            }
            binding.tvTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top + (24 * resources.displayMetrics.density).toInt()
            }
            binding.tvSubtitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                // Adjust subtitle starting point based on status bar
                topMargin = statusBars.top + (40 * resources.displayMetrics.density).toInt()
            }
            
            insets
        }

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.cardMotor.setOnClickListener {
            viewModel.vehicle = "motor"
            findNavController().navigate(R.id.sendFormFragment)
        }
        binding.cardCar.setOnClickListener {
            viewModel.vehicle = "car"
            findNavController().navigate(R.id.sendFormFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
