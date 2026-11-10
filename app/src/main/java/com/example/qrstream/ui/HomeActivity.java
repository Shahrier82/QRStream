package com.example.qrstream.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.example.qrstream.R;
import com.example.qrstream.databinding.ActivityHomeBinding;
import com.example.qrstream.receiver.TransferStatusReceiver;
import com.example.qrstream.service.TransferState;
import com.example.qrstream.util.AppPrefs;
import com.example.qrstream.util.AuthHelper;
import com.example.qrstream.util.Formatters;
import com.google.firebase.auth.FirebaseUser;

/** Home screen: Send, Receive and History, account status and the demo loss switch. */
public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;
    private AppPrefs prefs;

    private final TransferStatusReceiver statusReceiver = new TransferStatusReceiver(
            new TransferStatusReceiver.Listener() {
                @Override
                public void onProgress(TransferState state) {
                    binding.textTransferStatus.setVisibility(View.VISIBLE);
                    binding.textTransferStatus.setText(getString(R.string.home_transfer_running,
                            state.fileName == null ? getString(R.string.unknown_file) : state.fileName,
                            Formatters.formatPercent(state.percent)));
                }

                @Override
                public void onCompleted(TransferState state) {
                    binding.textTransferStatus.setVisibility(View.GONE);
                }

                @Override
                public void onFailed(TransferState state, String message) {
                    binding.textTransferStatus.setVisibility(View.GONE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = new AppPrefs(this);

        binding.buttonSend.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        binding.buttonReceive.setOnClickListener(v -> startActivity(new Intent(this, ReceiveActivity.class)));
        binding.buttonHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        binding.switchSimulateLoss.setChecked(prefs.isSimulateLoss());
        binding.switchSimulateLoss.setOnCheckedChangeListener(
                (button, checked) -> prefs.setSimulateLoss(checked));
    }

    @Override
    protected void onStart() {
        super.onStart();
        statusReceiver.register(this);
        binding.switchSimulateLoss.setChecked(prefs.isSimulateLoss());
        updateUserLine();
    }

    @Override
    protected void onStop() {
        statusReceiver.unregister(this);
        super.onStop();
    }

    private void updateUserLine() {
        FirebaseUser user = AuthHelper.currentUser();
        if (user != null && user.getEmail() != null) {
            binding.textUser.setText(getString(R.string.home_signed_in, user.getEmail()));
        } else {
            binding.textUser.setText(R.string.home_offline);
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_home, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem account = menu.findItem(R.id.action_account);
        account.setTitle(AuthHelper.currentUser() != null
                ? R.string.action_sign_out : R.string.action_sign_in);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_benchmark) {
            startActivity(new Intent(this, BenchmarkActivity.class));
            return true;
        }
        if (id == R.id.action_account) {
            AuthHelper.signOut();
            prefs.setOfflineMode(false);
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
