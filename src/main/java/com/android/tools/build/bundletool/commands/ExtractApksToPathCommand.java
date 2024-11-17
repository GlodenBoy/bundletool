package com.android.tools.build.bundletool.commands;

import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;
import static com.google.common.base.Preconditions.checkArgument;

import com.android.bundle.Devices.DeviceSpec;
import com.android.tools.build.bundletool.flags.Flag;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.model.exceptions.CommandExecutionException;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts APKs for a given device-spec and outputs them to a specified directory.
 */
public class ExtractApksToPathCommand {

    public static final String COMMAND_NAME = "extract-apk";

    // 定义命令行标志
    private static final Flag<Path> APKS_ARCHIVE_PATH_FLAG = Flag.path("apks");
    private static final Flag<Path> DEVICE_SPEC_PATH_FLAG = Flag.path("device-spec");
    private static final Flag<Path> OUTPUT_DIRECTORY_FLAG = Flag.path("output-dir");

    private final Path apksArchivePath;
    private final Path deviceSpecPath;
    private final Path outputDirectory;

    private ExtractApksToPathCommand(
            Path apksArchivePath, Path deviceSpecPath, Path outputDirectory) {
        this.apksArchivePath = apksArchivePath;
        this.deviceSpecPath = deviceSpecPath;
        this.outputDirectory = outputDirectory;
    }

    /**
     * 从 ParsedFlags 构建命令对象
     */
    public static ExtractApksToPathCommand fromFlags(ParsedFlags flags) {
        Path apksArchivePath = APKS_ARCHIVE_PATH_FLAG.getRequiredValue(flags);
        Path deviceSpecPath = DEVICE_SPEC_PATH_FLAG.getRequiredValue(flags);
        Path outputDirectory = OUTPUT_DIRECTORY_FLAG.getRequiredValue(flags);

        // 检查是否有未处理的标志
        flags.checkNoUnknownFlags();

        return new ExtractApksToPathCommand(apksArchivePath, deviceSpecPath, outputDirectory);
    }

    public void execute() {
        validateInput();

        try {
            DeviceSpec deviceSpec = readDeviceSpec(deviceSpecPath);

            ExtractApksCommand.Builder extractApksCommand =
                    ExtractApksCommand.builder()
                            .setApksArchivePath(apksArchivePath)
                            .setDeviceSpec(deviceSpec)
                            .setOutputDirectory(outputDirectory);

            extractApksCommand.build().execute();
        } catch (Exception e) {
            throw CommandExecutionException.builder()
                    .withCause(e)
                    .withInternalMessage("Failed to extract APKs to the specified path.")
                    .build();
        }
    }

    private void validateInput() {
        checkFileExistsAndReadable(apksArchivePath);
        checkFileExistsAndReadable(deviceSpecPath);
        checkArgument(
                Files.isDirectory(outputDirectory) || !Files.exists(outputDirectory),
                "Output directory must be a valid directory or a non-existing path.");
    }

    private DeviceSpec readDeviceSpec(Path deviceSpecPath) {
        try {
            String deviceSpecJson = Files.readString(deviceSpecPath);
            DeviceSpec.Builder deviceSpecBuilder = DeviceSpec.newBuilder();
            JsonFormat.parser().merge(deviceSpecJson, deviceSpecBuilder);
            return deviceSpecBuilder.build();
        } catch (IOException e) {
            throw CommandExecutionException.builder()
                    .withCause(e)
                    .withInternalMessage("Failed to read device-spec.json from '%s'.", deviceSpecPath)
                    .build();
        }
    }
}
