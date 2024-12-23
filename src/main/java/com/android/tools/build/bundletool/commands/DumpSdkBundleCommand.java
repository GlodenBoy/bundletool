/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.tools.build.bundletool.commands;

import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.Function.identity;

import com.android.tools.build.bundletool.commands.CommandHelp.CommandDescription;
import com.android.tools.build.bundletool.commands.CommandHelp.FlagDescription;
import com.android.tools.build.bundletool.flags.Flag;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.model.ResourceTableEntry;
import com.android.tools.build.bundletool.model.exceptions.InvalidCommandException;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command that prints information about a given Android SDK Bundle. */
@AutoValue
public abstract class DumpSdkBundleCommand {

  public static final String COMMAND_NAME = "dump-sdk-bundle";

  private static final Flag<Path> BUNDLE_LOCATION_FLAG = Flag.path("bundle");
  private static final Flag<String> XPATH_FLAG = Flag.string("xpath");
  private static final Flag<String> RESOURCE_FLAG = Flag.string("resource");
  private static final Flag<Boolean> VALUES_FLAG = Flag.booleanFlag("values");

  private static final Pattern RESOURCE_NAME_PATTERN =
      Pattern.compile("(?<type>[^/]+)/(?<name>[^/]+)");

  public abstract Path getBundlePath();

  public abstract PrintStream getOutputStream();

  public abstract DumpTarget getDumpTarget();

  public abstract Optional<String> getXPathExpression();

  public abstract Optional<Integer> getResourceId();

  public abstract Optional<String> getResourceName();

  public abstract Optional<Boolean> getPrintValues();

  public static Builder builder() {
    return new AutoValue_DumpSdkBundleCommand.Builder().setOutputStream(System.out);
  }

  /** Builder for the {@link DumpSdkBundleCommand}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /** Sets the path to the bundle. */
    public abstract Builder setBundlePath(Path bundlePath);

    /** Sets the output stream where the dump should be printed. */
    public abstract Builder setOutputStream(PrintStream outputStream);

    /** Sets the target of the dump, e.g. the manifest. */
    public abstract Builder setDumpTarget(DumpTarget dumpTarget);

    /** Sets the XPath expression used to extract only part of the XML file being printed. */
    public abstract Builder setXPathExpression(String xPathExpression);

    /**
     * Sets the ID of the resource to print.
     *
     * <p>Mutually exclusive with {@link #setResourceName}.
     */
    public abstract Builder setResourceId(int resourceId);

    /**
     * Sets the name of the resource to print. Must have the format "<type>/<name>", e.g.
     * "drawable/icon".
     *
     * <p>Mutually exclusive with {@link #setResourceId}.
     */
    public abstract Builder setResourceName(String resourceName);

    /** Sets whether the values should also be printed when printing the resources. */
    public abstract Builder setPrintValues(boolean printValues);

    public abstract DumpSdkBundleCommand build();
  }

  public static DumpSdkBundleCommand fromFlags(ParsedFlags flags) {
    DumpTarget dumpTarget = parseDumpTarget(flags);

    Path bundlePath = BUNDLE_LOCATION_FLAG.getRequiredValue(flags);
    Optional<String> xPath = XPATH_FLAG.getValue(flags);
    Optional<String> resource = RESOURCE_FLAG.getValue(flags);
    Optional<Boolean> printValues = VALUES_FLAG.getValue(flags);

    DumpSdkBundleCommand.Builder dumpCommand =
        DumpSdkBundleCommand.builder().setBundlePath(bundlePath).setDumpTarget(dumpTarget);

    xPath.ifPresent(dumpCommand::setXPathExpression);
    printValues.ifPresent(dumpCommand::setPrintValues);
    resource.ifPresent(
        r -> {
          try {
            // Using Long.decode to support negative resource IDs specified in hexadecimal.
            dumpCommand.setResourceId(Long.decode(r).intValue());
          } catch (NumberFormatException e) {
            dumpCommand.setResourceName(r);
          }
        });

    return dumpCommand.build();
  }

  public void execute() {
    validateInput();

    switch (getDumpTarget()) {
      case CONFIG:
        new DumpSdkBundleManager(getOutputStream(), getBundlePath()).printBundleConfig();
        break;

      case MANIFEST:
        new DumpSdkBundleManager(getOutputStream(), getBundlePath())
            .printManifest(getXPathExpression());
        break;

      case RESOURCES:
        new DumpSdkBundleManager(getOutputStream(), getBundlePath())
            .printResources(parseResourcePredicate(), getPrintValues().orElse(false));
        break;
    }
  }

  private void validateInput() {
    checkFileExistsAndReadable(getBundlePath());

    if (getResourceId().isPresent() && getResourceName().isPresent()) {
      throw InvalidCommandException.builder()
          .withInternalMessage("Cannot pass both resource ID and resource name. Pick one!")
          .build();
    }
    if (getDumpTarget().equals(DumpTarget.RESOURCES) && getXPathExpression().isPresent()) {
      throw InvalidCommandException.builder()
          .withInternalMessage("Cannot pass an XPath expression when dumping resources.")
          .build();
    }
    if (!getDumpTarget().equals(DumpTarget.RESOURCES)
        && (getResourceId().isPresent() || getResourceName().isPresent())) {
      throw InvalidCommandException.builder()
          .withInternalMessage("The resource name/id can only be passed when dumping resources.")
          .build();
    }
    if (!getDumpTarget().equals(DumpTarget.RESOURCES) && getPrintValues().isPresent()) {
      throw InvalidCommandException.builder()
          .withInternalMessage(
              "Printing resource values can only be requested when dumping resources.")
          .build();
    }
  }

  private static DumpTarget parseDumpTarget(ParsedFlags flags) {
    String subCommand =
        flags
            .getSubCommand()
            .orElseThrow(
                () ->
                    InvalidCommandException.builder()
                        .withInternalMessage("Target of the dump not found.")
                        .build());

    return DumpTarget.fromString(subCommand);
  }

  private Predicate<ResourceTableEntry> parseResourcePredicate() {
    if (getResourceId().isPresent()) {
      return entry -> entry.getResourceId().getFullResourceId() == getResourceId().get().intValue();
    }

    if (getResourceName().isPresent()) {
      String resourceName = getResourceName().get();
      Matcher matcher = RESOURCE_NAME_PATTERN.matcher(resourceName);
      if (!matcher.matches()) {
        throw InvalidCommandException.builder()
            .withInternalMessage(
                "Resource name must match the format '<type>/<name>', e.g. 'drawable/icon'.")
            .build();
      }
      return entry ->
          entry.getType().getName().equals(matcher.group("type"))
              && entry.getEntry().getName().equals(matcher.group("name"));
    }

    return entry -> true;
  }

  /** Target of the dump. */
  public enum DumpTarget {
    MANIFEST("manifest"),
    RESOURCES("resources"),
    CONFIG("config");

    static final ImmutableMap<String, DumpTarget> SUBCOMMAND_TO_TARGET =
        Arrays.stream(DumpTarget.values())
            .collect(toImmutableMap(DumpTarget::toString, identity()));

    private final String subCommand;

    DumpTarget(String subCommand) {
      this.subCommand = subCommand;
    }

    @Override
    public String toString() {
      return subCommand;
    }

    public static DumpTarget fromString(String subCommand) {
      DumpTarget dumpTarget = SUBCOMMAND_TO_TARGET.get(subCommand);
      if (dumpTarget == null) {
        throw InvalidCommandException.builder()
            .withInternalMessage(
                "Unrecognized dump target: '%s'. Accepted values are: %s",
                subCommand, SUBCOMMAND_TO_TARGET.keySet())
            .build();
      }
      return dumpTarget;
    }
  }

  public static CommandHelp help() {
    return CommandHelp.builder()
        .setCommandName(COMMAND_NAME)
        .setSubCommandNames(DumpTarget.SUBCOMMAND_TO_TARGET.keySet().asList())
        .setCommandDescription(
            CommandDescription.builder()
                .setShortDescription(
                    "Prints files or extract values from the SDK bundle in a human-readable form.")
                .addAdditionalParagraph("Examples:")
                .addAdditionalParagraph(
                    String.format(
                        "1. Prints the AndroidManifest.xml of the SDK bundle:%n"
                            + "$ bundletool dump-sdk-bundle manifest --bundle=/tmp/sdk.asb"))
                .addAdditionalParagraph(
                    String.format(
                        "2. Prints the package of the SDK bundle:%n"
                            + "$ bundletool dump-sdk-bundle manifest --bundle=/tmp/sdk.asb "
                            + "--xpath=/manifest/@package"))
                .addAdditionalParagraph(
                    String.format(
                        "3. Prints all the resources present in the SDK bundle:%n"
                            + "$ bundletool dump-sdk-bundle resources --bundle=/tmp/sdk.asb"))
                .addAdditionalParagraph(
                    String.format(
                        "4. Prints a resource's configs from its resource ID:%n"
                            + "$ bundletool dump-sdk-bundle resources --bundle=/tmp/sdk.asb "
                            + "--resource=0x7f0e013a"))
                .addAdditionalParagraph(
                    String.format(
                        "5. Prints a resource's configs and values from its resource type & name:%n"
                            + "$ bundletool dump-sdk-bundle resources --bundle=/tmp/sdk.asb "
                            + "--resource=drawable/icon --values"))
                .addAdditionalParagraph(
                    String.format(
                        "6. Prints the content of the SDK bundle configuration file:%n"
                            + "$ bundletool dump-sdk-bundle config --bundle=/tmp/sdk.asb"))
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName("bundle")
                .setDescription("Path to the SDK Bundle.")
                .setExampleValue("sdk.asb")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName("xpath")
                .setDescription(
                    "XPath expression to extract the value of attributes from the XML file being "
                        + "dumped. Only applies when dumping the manifest.")
                .setExampleValue("/manifest/@package")
                .setOptional(true)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName("resource")
                .setDescription(
                    "Name or ID of the resource to lookup. Only applies when dumping resources. If "
                        + "a resource ID is provided, it can be specified either as a decimal or "
                        + "hexadecimal integer. If a resource name is provided, it must follow the "
                        + "format '<type>/<name>', e.g. 'drawable/icon'")
                .setExampleValue("0x7f030001")
                .setOptional(true)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName("values")
                .setDescription(
                    "When set, also prints the values of the resources. Defaults to false. "
                        + "Only applies when dumping the resources.")
                .setOptional(true)
                .build())
        .build();
  }
}
