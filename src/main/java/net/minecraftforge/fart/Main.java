/*
 * Forge Auto Renaming Tool
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fart;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;
import net.minecraftforge.fart.api.IdentifierFixerConfig;
import net.minecraftforge.fart.api.RecordFixFlag;
import net.minecraftforge.fart.api.Renamer;
import net.minecraftforge.fart.api.SourceFixerConfig;
import net.minecraftforge.fart.api.Transformer;
import net.minecraftforge.srgutils.IMappingFile;

public class Main {
    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> inputO  = parser.accepts("input",  "Input jar file").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output", "Output jar file, if unspecifed, overwrites input").withRequiredArg().ofType(File.class);
        OptionSpec<File> mapO    = parser.acceptsAll(Arrays.asList("map", "names"),    "Mapping file to apply").withRequiredArg().ofType(File.class);
        OptionSpec<File> logO    = parser.accepts("log",    "File to log data to, optional, defaults to System.out").withRequiredArg().ofType(File.class);
        OptionSpec<File> libO    = parser.acceptsAll(Arrays.asList("lib", "e"), "Additional library to use for inheritence").withRequiredArg().ofType(File.class);
        OptionSpec<Void> fixAnnO = parser.accepts("ann-fix", "Fixes misaligned parameter annotations caused by Proguard.");
        OptionSpec<RecordFixFlag> fixRecordsO = parser.accepts("record-fix", "Fixes record component data stripped by Proguard.").withOptionalArg().withValuesSeparatedBy(',').withValuesConvertedBy(new RecordConverter()).defaultsTo(RecordFixFlag.COMPONENTS, RecordFixFlag.SIGNATURE);
        OptionSpec<IdentifierFixerConfig> fixIdsO = parser.accepts("ids-fix", "Fixes local variables that are not valid java identifiers.").withOptionalArg().withValuesConvertedBy(new IDConverter()).defaultsTo(IdentifierFixerConfig.ALL);
        OptionSpec<SourceFixerConfig> fixSrcO = parser.accepts("src-fix", "Fixes the 'SourceFile' attribute of classes.").withOptionalArg().withValuesConvertedBy(new SrcConverter()).defaultsTo(SourceFixerConfig.JAVA);
        OptionSpec<Integer> threadsO = parser.accepts("threads", "Number of threads to use, defaults to processor count.").withRequiredArg().ofType(Integer.class).defaultsTo(Runtime.getRuntime().availableProcessors());
        OptionSpec<File> ffLinesO = parser.accepts("ff-line-numbers", "Applies line number corrections from Fernflower.").withRequiredArg().ofType(File.class);
        OptionSpec<Void> reverseO = parser.accepts("reverse", "Reverse provided mapping file before applying");
        OptionSet options = parser.parse(expandArgs(args));

        Consumer<String> log = ln -> {
            if (!ln.isEmpty()) {
                System.out.println(ln);
            }
        };
        if (options.has(logO)) {
            PrintStream out = System.out;
            PrintStream file = new PrintStream(new FileOutputStream(options.valueOf(logO)));
            log = ln -> {
                if (!ln.isEmpty()) {
                    out.println(ln);
                    file.println(ln);
                }
            };
        }

        log.accept("Forge Auto Renaming Tool v" + getVersion());
        Renamer.Builder builder = Renamer.builder();
        builder.logger(log);

        // Move this up top so that the log lines are above the rest of the config as they can be spammy.
        // Its useful information but we care more about the specific configs.
        if (options.has(libO)) {
            for (File lib : options.valuesOf(libO)) {
                log.accept("lib: " + lib.getAbsolutePath());
                builder.lib(lib);
            }
        }

        log.accept("log: " + (options.has(logO) ? options.valueOf(logO).getAbsolutePath() : "null"));

        File inputF = options.valueOf(inputO);
        log.accept("input: " + inputF.getAbsolutePath());
        builder.input(inputF);

        File outputF = options.has(outputO) ? options.valueOf(outputO) : inputF;
        log.accept("output: " + outputF.getAbsolutePath());
        builder.output(outputF);

        log.accept("threads: " + options.valueOf(threadsO));
        builder.threads(options.valueOf(threadsO));

        // Map is optional so that we can run other fixes without renaming.
        // This does mean that it's not strictly a 'renaming' tool but screw it I like the name.
        if (options.has(mapO)) {
            File mapF = options.valueOf(mapO);
            log.accept("Names: " + mapF.getAbsolutePath() + "(reversed: " + options.has(reverseO) + ")");
            IMappingFile mappings = IMappingFile.load(mapF);
            if (options.has(reverseO)) {
                mappings = mappings.reverse();
            }

            builder.add(Transformer.renamerFactory(mappings));
        } else {
            log.accept("Names: null");
        }

        if (options.has(fixAnnO)) {
            log.accept("Fix Annotations: true");
            builder.add(Transformer.parameterAnnotationFixerFactory());
        } else {
            log.accept("Fix Annotations: false");
        }

        if (options.has(fixRecordsO)) {
            log.accept("Fix Records: true");
            builder.add(Transformer.recordFixerFactory(EnumSet.copyOf(options.valuesOf(fixRecordsO))));
        } else {
            log.accept("Fix Records: false");
        }

        if (options.has(fixIdsO)) {
            log.accept("Fix Identifiers: " + options.valueOf(fixIdsO));
            builder.add(Transformer.identifierFixerFactory(options.valueOf(fixIdsO)));
        } else {
            log.accept("Fix Identifiers: false");
        }

        if (options.has(fixSrcO)) {
            log.accept("Fix SourceFile: " + options.valueOf(fixSrcO));
            builder.add(Transformer.sourceFixerFactory(options.valueOf(fixSrcO)));
        } else {
            log.accept("Fix SourceFile: false");
        }

        if (options.has(ffLinesO)) {
            File lines = options.valueOf(ffLinesO);
            log.accept("Fix Line Numbers: " + lines.getAbsolutePath());
            builder.add(Transformer.fernFlowerLineFixerFactory(lines));
        } else {
            log.accept("Fix Line Numbers: false");
        }

        Renamer renamer = builder.build();
        renamer.run();
    }

    private static String[] expandArgs(String[] args) throws IOException {
        List<String> ret = new ArrayList<>();
        for (int x = 0; x < args.length; x++) {
            if (args[x].equals("--cfg")) {
                if (x + 1 == args.length)
                    throw new IllegalArgumentException("No value specified for '--cfg'");

                Files.lines(Paths.get(args[++x])).forEach(ret::add);
            } else if (args[x].startsWith("--cfg=")) {
                Files.lines(Paths.get(args[x].substring(6))).forEach(ret::add);
            } else {
                ret.add(args[x]);
            }
        }

        return ret.toArray(new String[ret.size()]);
    }

    private static String getVersion() {
        final String ver = Main.class.getPackage().getImplementationVersion();
        return ver == null ? "UNKNOWN" : ver;
    }

    private static class IDConverter implements ValueConverter<IdentifierFixerConfig> {
        @Override
        public IdentifierFixerConfig convert(String value) {
            return IdentifierFixerConfig.valueOf(value.toUpperCase(Locale.ENGLISH));
        }

        @Override
        public Class<? extends IdentifierFixerConfig> valueType() {
            return IdentifierFixerConfig.class;
        }

        @Override
        public String valuePattern() {
            return Arrays.stream(IdentifierFixerConfig.values()).map(Enum::name).collect(Collectors.joining("|"));
        }
    }

    private static class SrcConverter implements ValueConverter<SourceFixerConfig> {
        @Override
        public SourceFixerConfig convert(String value) {
            return SourceFixerConfig.valueOf(value.toUpperCase(Locale.ENGLISH));
        }

        @Override
        public Class<? extends SourceFixerConfig> valueType() {
            return SourceFixerConfig.class;
        }

        @Override
        public String valuePattern() {
            return Arrays.stream(SourceFixerConfig.values()).map(Enum::name).collect(Collectors.joining("|"));
        }
    }

    private static class RecordConverter implements ValueConverter<RecordFixFlag> {
        @Override
        public RecordFixFlag convert(String value) {
            return RecordFixFlag.valueOf(value.toUpperCase(Locale.ENGLISH));
        }

        @Override
        public Class<? extends RecordFixFlag> valueType() {
            return RecordFixFlag.class;
        }

        @Override
        public String valuePattern() {
            return Arrays.stream(RecordFixFlag.values()).map(Enum::name).collect(Collectors.joining("|"));
        }
    }
}
