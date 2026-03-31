package com.jamillabltd.copilot_textproject;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.jamillabltd.copilot_textproject.databinding.LayoutBottomSheetDownloadBinding;

public class BottomSheetDownloadOptions extends BottomSheetDialogFragment {

    private LayoutBottomSheetDownloadBinding binding;
    private OnDownloadListener listener;
    private String currentPath;

    public interface OnDownloadListener {
        void onConfirm();
        void onChangeLocation();
        void onCancel();
    }

    public static BottomSheetDownloadOptions newInstance(String path) {
        BottomSheetDownloadOptions fragment = new BottomSheetDownloadOptions();
        Bundle args = new Bundle();
        args.putString("path", path);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnDownloadListener) {
            listener = (OnDownloadListener) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = LayoutBottomSheetDownloadBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getArguments() != null) {
            currentPath = getArguments().getString("path");
            binding.tvSheetPath.setText(currentPath);
        }

        binding.btnSheetConfirm.setOnClickListener(v -> {
            if (listener != null) listener.onConfirm();
            dismiss();
        });

        binding.btnSheetChange.setOnClickListener(v -> {
            if (listener != null) listener.onChangeLocation();
            dismiss();
        });

        binding.btnSheetCancel.setOnClickListener(v -> {
            if (listener != null) listener.onCancel();
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
