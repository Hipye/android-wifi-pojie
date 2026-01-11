package wifi.pojie;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DictionarySplitterActivity extends AppCompatActivity {
    private View topPlaceholder;
    private Button selectFileButton;
    private TextView selectedFileText;
    private EditText linesPerFileInput;
    private Button decreaseButton;
    private Button increaseButton;
    private TextView fileInfoText;
    private Button splitButton;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView resultText;

    private Uri selectedFileUri;
    private String selectedFileName;
    private int totalLines;
    private int linesPerFile;

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_splitter);

        ImageButton backButton = findViewById(R.id.back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        android.view.Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(ContextCompat.getColor(this, R.color.background));
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }

        topPlaceholder = findViewById(R.id.top_placeholder);
        selectFileButton = findViewById(R.id.select_file_button);
        selectedFileText = findViewById(R.id.selected_file_text);
        linesPerFileInput = findViewById(R.id.lines_per_file_input);
        decreaseButton = findViewById(R.id.decrease_button);
        increaseButton = findViewById(R.id.increase_button);
        fileInfoText = findViewById(R.id.file_info_text);
        splitButton = findViewById(R.id.split_button);
        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);
        resultText = findViewById(R.id.result_text);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            if (topPlaceholder != null) {
                android.view.ViewGroup.LayoutParams topParams = topPlaceholder.getLayoutParams();
                topParams.height = systemBars.top;
                topPlaceholder.setLayoutParams(topParams);
            }
            return windowInsets;
        });

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) {
                            selectedFileUri = uri;
                            selectedFileName = getFileNameFromUri(uri);
                            selectedFileText.setText(selectedFileName);
                            fileInfoText.setVisibility(View.VISIBLE);
                            fileInfoText.setText("正在统计行数...");
                            executorService = Executors.newSingleThreadExecutor();
                            executorService.execute(() -> {
                                try {
                                    totalLines = countLines(uri);
                                    runOnUiThread(() -> {
                                        fileInfoText.setText("文件: " + selectedFileName + "\n总行数: " + totalLines);
                                        splitButton.setEnabled(true);
                                    });
                                } catch (IOException e) {
                                    runOnUiThread(() -> {
                                        fileInfoText.setText("读取文件失败: " + e.getMessage());
                                    });
                                }
                            });
                        }
                    }
                }
        );

        selectFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(Intent.createChooser(intent, "选择字典文件"));
        });

        decreaseButton.setOnClickListener(v -> {
            try {
                int current = Integer.parseInt(linesPerFileInput.getText().toString());
                if (current > 100) {
                    linesPerFileInput.setText(String.valueOf(current - 100));
                }
            } catch (NumberFormatException e) {
                linesPerFileInput.setText("2000");
            }
        });

        increaseButton.setOnClickListener(v -> {
            try {
                int current = Integer.parseInt(linesPerFileInput.getText().toString());
                linesPerFileInput.setText(String.valueOf(current + 100));
            } catch (NumberFormatException e) {
                linesPerFileInput.setText("2000");
            }
        });

        splitButton.setOnClickListener(v -> {
            try {
                linesPerFile = Integer.parseInt(linesPerFileInput.getText().toString());
                if (linesPerFile < 1) {
                    linesPerFileInput.setError("每文件行数必须大于0");
                    return;
                }
                startSplit();
            } catch (NumberFormatException e) {
                linesPerFileInput.setError("请输入有效的数字");
            }
        });
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private int countLines(Uri uri) throws IOException {
        int count = 0;
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            while (reader.readLine() != null) {
                count++;
            }
        }
        return count;
    }

    private void startSplit() {
        splitButton.setEnabled(false);
        selectFileButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        resultText.setText("");
        progressText.setText("正在读取文件...");

        executorService.execute(() -> {
            try {
                List<String> outputFiles = splitDictionary(selectedFileUri, selectedFileName, linesPerFile);
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    progressText.setVisibility(View.GONE);
                    splitButton.setEnabled(true);
                    selectFileButton.setEnabled(true);

                    StringBuilder result = new StringBuilder();
                    result.append("分割完成！\n\n");
                    result.append("原文件: ").append(selectedFileName).append("\n");
                    result.append("总行数: ").append(totalLines).append("\n");
                    result.append("每文件行数: ").append(linesPerFile).append("\n");
                    result.append("生成文件数: ").append(outputFiles.size()).append("\n\n");
                    result.append("生成的文件:\n");
                    for (String file : outputFiles) {
                        result.append("• ").append(file).append("\n");
                    }
                    resultText.setText(result.toString());
                });
            } catch (IOException e) {
                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    progressText.setVisibility(View.GONE);
                    splitButton.setEnabled(true);
                    selectFileButton.setEnabled(true);
                    resultText.setText("分割失败: " + e.getMessage());
                });
            }
        });
    }

    private List<String> splitDictionary(Uri inputUri, String inputFileName, int linesPerFile) throws IOException {
        List<String> outputFiles = new ArrayList<>();

        String baseName = inputFileName;
        String ext = "";
        int dotIndex = inputFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = inputFileName.substring(0, dotIndex);
            ext = inputFileName.substring(dotIndex);
        }

        int fileIndex = 0;
        int currentLine = 0;
        int totalProcessed = 0;
        BufferedWriter currentWriter = null;
        String currentFileName = null;

        try (InputStream inputStream = getContentResolver().openInputStream(inputUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (currentLine == 0 || currentLine >= linesPerFile) {
                    if (currentWriter != null) {
                        currentWriter.close();
                    }

                    fileIndex++;
                    currentFileName = String.format("%s_%02d%s", baseName, fileIndex, ext);

                    Uri outputUri = createFileInDownloads(currentFileName);
                    if (outputUri == null) {
                        throw new IOException("无法创建文件: " + currentFileName);
                    }

                    currentWriter = new BufferedWriter(new OutputStreamWriter(getContentResolver().openOutputStream(outputUri)));
                    outputFiles.add(currentFileName);
                    currentLine = 0;

                    final int progress = totalProcessed;
                    final int total = totalLines;
                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        progressBar.setProgress(progress);
                        progressBar.setMax(total);
                        progressText.setText(String.format("处理中: %d / %d 行", progress, total));
                    });
                }

                if (currentWriter != null) {
                    currentWriter.write(line);
                    currentWriter.newLine();
                }
                currentLine++;
                totalProcessed++;
            }

            if (currentWriter != null) {
                currentWriter.close();
            }
        }

        return outputFiles;
    }

    private Uri createFileInDownloads(String fileName) {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
