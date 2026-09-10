// Exports the human-made annotations in the current program to text files in
// <repo>/annotations/ so they can be versioned in git. The Ghidra database itself stays out
// of git because it embeds the game binary; ApplyAnnotations.java replays these files onto a
// fresh import of the user's own copy.
//
// What gets exported:
//   functions.jsonl  functions whose name and/or signature is USER_DEFINED
//   labels.jsonl     USER_DEFINED labels (non-function symbols)
//   data.jsonl       defined data whose type is one of ours (under /prototype)
//   comments.jsonl   comments that auto-analysis did not create (see "comment baseline")
//   types.h          every data type under the /prototype category, as C
//   types.jsonl      per type: its category, description and member comments, which the C
//                    header can't carry through Ghidra's C parser
//
// Comment baseline: comments have no "who made this" flag, and auto-analysis creates
// hundreds (PE header and import annotations). So right after analysis, setup runs this
// script with the single argument "record-comment-baseline", which snapshots every comment
// into <project dir>/<program>.comment-baseline.jsonl (local, gitignored). Later exports
// skip comments that are still exactly as in that snapshot.
//
// Not exported yet: local variable names/types inside function bodies (parameters are
// covered by the signature).
//
// Optional script argument: output directory (default: <repo>/annotations).
//@category Prototype

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import ghidra.app.script.GhidraScript;
import ghidra.framework.model.ProjectLocator;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class ExportAnnotations extends GhidraScript {

	// All of our recovered types live under this category; everything else in the program's
	// type manager came from Ghidra (built-ins, Windows headers, auto-analysis).
	private static final String TYPES_ROOT = "/prototype";
	private static final String RECORD_BASELINE = "record-comment-baseline";

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

	@Override
	protected void run() throws Exception {
		String[] args = getScriptArgs();
		if (args.length > 0 && args[0].equals(RECORD_BASELINE)) {
			List<String> all = exportComments(Set.of());
			write(baselineFile(), all);
			println("Recorded " + all.size() + " analysis comments as the baseline in " +
				baselineFile());
			return;
		}

		Path dir = args.length > 0 ? Path.of(args[0]) : defaultAnnotationsDir();
		Files.createDirectories(dir);

		List<String> functions = exportFunctions();
		List<String> labels = exportLabels();
		List<String> data = exportData();
		List<String> comments = exportComments(readBaseline());
		int types = exportTypes(dir);

		write(dir.resolve("functions.jsonl"), functions);
		write(dir.resolve("labels.jsonl"), labels);
		write(dir.resolve("data.jsonl"), data);
		write(dir.resolve("comments.jsonl"), comments);

		println(String.format("Exported %d functions, %d labels, %d data, %d comments, %d types to %s",
			functions.size(), labels.size(), data.size(), comments.size(), types, dir));
	}

	private Path defaultAnnotationsDir() {
		// This file is <repo>/tools/ghidra/ExportAnnotations.java.
		File repo = getSourceFile().getParentFile().getParentFile().getParentFile().getFile(false);
		return repo.toPath().resolve("annotations");
	}

	private Path baselineFile() {
		ProjectLocator project = getState().getProject().getProjectLocator();
		return Path.of(project.getLocation(),
			currentProgram.getName() + ".comment-baseline.jsonl");
	}

	private Set<String> readBaseline() throws IOException {
		Path file = baselineFile();
		if (!Files.exists(file)) {
			printerr("No comment baseline at " + file + ": exporting ALL comments, including " +
				"the ones auto-analysis created.");
			return Set.of();
		}
		return new HashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8));
	}

	private List<String> exportFunctions() throws Exception {
		List<String> lines = new ArrayList<>();
		for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
			if (f.isThunk()) {
				continue; // a thunk's name and signature follow its target
			}
			boolean userName = f.getSymbol().getSource() == SourceType.USER_DEFINED;
			boolean userSignature = f.getSignatureSource() == SourceType.USER_DEFINED;
			if (!userName && !userSignature) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("addr", f.getEntryPoint().toString());
			if (userName) {
				o.addProperty("name", f.getName(true));
			}
			if (userSignature) {
				// Formal signature = without auto-parameters such as __thiscall's `this`.
				FunctionDefinitionDataType def = new FunctionDefinitionDataType(f, true);
				def.setName("f"); // the real name is in "name"; a plain one keeps this parseable
				o.addProperty("signature", def.getPrototypeString(false));
				o.addProperty("callingConvention", f.getCallingConventionName());
				if (f.hasNoReturn()) {
					o.addProperty("noReturn", true);
				}
			}
			lines.add(gson.toJson(o));
		}
		return lines;
	}

	private List<String> exportLabels() {
		List<String> lines = new ArrayList<>();
		for (Symbol s : currentProgram.getSymbolTable().getAllSymbols(false)) {
			if (s.getSymbolType() != SymbolType.LABEL || s.getSource() != SourceType.USER_DEFINED ||
				s.isExternal()) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("addr", s.getAddress().toString());
			o.addProperty("name", s.getName(true));
			o.addProperty("primary", s.isPrimary());
			lines.add(gson.toJson(o));
		}
		Collections.sort(lines); // symbol table order is not address order
		return lines;
	}

	private List<String> exportData() {
		List<String> lines = new ArrayList<>();
		for (Data d : currentProgram.getListing().getDefinedData(true)) {
			if (!isOurs(d.getDataType())) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("addr", d.getAddress().toString());
			o.addProperty("type", d.getDataType().getPathName());
			lines.add(gson.toJson(o));
		}
		return lines;
	}

	private List<String> exportComments(Set<String> baseline) {
		List<String> lines = new ArrayList<>();
		Listing listing = currentProgram.getListing();
		for (Address a : listing.getCommentAddressIterator(currentProgram.getMemory(), true)) {
			for (CommentType type : CommentType.values()) {
				String text = listing.getComment(type, a);
				if (text == null) {
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("addr", a.toString());
				o.addProperty("type", type.name());
				o.addProperty("text", text);
				String line = gson.toJson(o);
				if (!baseline.contains(line)) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	private int exportTypes(Path dir) throws Exception {
		DataTypeManager dtm = currentProgram.getDataTypeManager();
		List<DataType> types = new ArrayList<>();
		Category root = dtm.getCategory(new CategoryPath(TYPES_ROOT));
		if (root != null) {
			collectTypes(root, types);
		}
		types.sort(Comparator.comparing(DataType::getPathName));

		StringWriter header = new StringWriter();
		header.write("// Generated by tools/ghidra/ExportAnnotations.java from the " + TYPES_ROOT +
			" category.\n// Edit the types in Ghidra and re-export; hand edits here are overwritten.\n\n");
		if (!types.isEmpty()) {
			new DataTypeWriter(dtm, header).write(types, monitor);
		}
		Files.writeString(dir.resolve("types.h"), header.toString(), StandardCharsets.UTF_8);

		List<String> meta = new ArrayList<>();
		for (DataType dt : types) {
			JsonObject o = new JsonObject();
			o.addProperty("name", dt.getName());
			o.addProperty("category", dt.getCategoryPath().getPath());
			// A typedef's description is derived ("pointer to X") and can't be set.
			String description = dt instanceof TypeDef ? null : dt.getDescription();
			if (description != null && !description.isEmpty()) {
				o.addProperty("description", description);
			}
			JsonObject members = memberComments(dt);
			if (members.size() > 0) {
				o.add("memberComments", members);
			}
			meta.add(gson.toJson(o));
		}
		write(dir.resolve("types.jsonl"), meta);
		return types.size();
	}

	private static JsonObject memberComments(DataType dt) {
		JsonObject members = new JsonObject();
		if (dt instanceof Composite composite) {
			for (DataTypeComponent c : composite.getDefinedComponents()) {
				if (c.getComment() != null && !c.getComment().isEmpty()) {
					members.addProperty(fieldKey(c), c.getComment());
				}
			}
		}
		else if (dt instanceof ghidra.program.model.data.Enum e) {
			for (String name : e.getNames()) {
				String comment = e.getComment(name);
				if (comment != null && !comment.isEmpty()) {
					members.addProperty(name, comment);
				}
			}
		}
		return members;
	}

	static String fieldKey(DataTypeComponent c) {
		return c.getFieldName() != null ? c.getFieldName() : c.getDefaultFieldName();
	}

	private static void collectTypes(Category category, List<DataType> out) {
		for (DataType dt : category.getDataTypes()) {
			// Pointers and arrays of our types are derived on demand and filed next to them;
			// they are not definitions of their own.
			if (!(dt instanceof Pointer) && !(dt instanceof Array)) {
				out.add(dt);
			}
		}
		for (Category sub : category.getCategories()) {
			collectTypes(sub, out);
		}
	}

	static boolean isOurs(DataType dt) {
		while (dt != null) {
			String category = dt.getCategoryPath().getPath();
			if (category.equals(TYPES_ROOT) || category.startsWith(TYPES_ROOT + "/")) {
				return true;
			}
			if (dt instanceof Pointer p) {
				dt = p.getDataType();
			}
			else if (dt instanceof Array a) {
				dt = a.getDataType();
			}
			else {
				return false;
			}
		}
		return false;
	}

	private static void write(Path file, List<String> lines) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			sb.append(line).append('\n');
		}
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
	}
}
