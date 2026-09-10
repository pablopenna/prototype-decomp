// Replays the files written by ExportAnnotations.java onto the current program: data types
// first (everything else may reference them), then functions, labels, typed data, comments.
//
// Each entry overwrites what the program has at that address; nothing else is touched. So on
// an existing project, export before applying if you have un-exported work — otherwise work
// at the same addresses is replaced by the version from git.
//
// Optional script argument: annotations directory (default: <repo>/annotations).
//@category Prototype

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.function.FunctionRenameOption;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.NamespaceUtils;
import ghidra.app.util.SymbolPath;
import ghidra.app.util.cparser.C.CParser;
import ghidra.app.util.parser.FunctionSignatureParser;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.InvalidInputException;

public class ApplyAnnotations extends GhidraScript {

	// "Outer[2][3]" = 2 elements of "Outer[3]": the first bracket is the outermost dimension.
	private static final Pattern ARRAY = Pattern.compile("^(.*?)\\[(\\d+)\\](.*)$");

	@Override
	protected void run() throws Exception {
		String[] args = getScriptArgs();
		Path dir = args.length > 0 ? Path.of(args[0]) : defaultAnnotationsDir();
		if (!Files.isDirectory(dir)) {
			println("No annotations at " + dir + "; nothing to apply.");
			return;
		}
		int types = applyTypes(dir);
		int functions = applyFunctions(dir);
		int labels = applyLabels(dir);
		int data = applyData(dir);
		int comments = applyComments(dir);
		println(String.format("Applied %d types, %d functions, %d labels, %d data, %d comments from %s",
			types, functions, labels, data, comments, dir));
	}

	private Path defaultAnnotationsDir() {
		// The project lives in <repo>/ghidra. Derived from the project rather than from this
		// file's location because the MCP plugin may run a copy of the script from elsewhere.
		Path projectDir = Path.of(getState().getProject().getProjectLocator().getLocation());
		return projectDir.getParent().resolve("annotations");
	}

	private int applyTypes(Path dir) throws Exception {
		Map<String, JsonObject> meta = new HashMap<>();
		for (JsonObject o : readJsonl(dir.resolve("types.jsonl"))) {
			meta.put(o.get("name").getAsString(), o);
		}
		Path header = dir.resolve("types.h");
		if (meta.isEmpty() || !Files.exists(header)) {
			return 0;
		}

		DataTypeManager dtm = currentProgram.getDataTypeManager();
		CParser parser = new CParser(dtm, false, null);
		try (InputStream in = Files.newInputStream(header)) {
			parser.parse(in);
		}

		// Restore what the C round trip loses (category, description, member comments) on
		// the parsed types *before* resolving, so types that reference each other all land in
		// the right place together. Types the header merely depends on (not in types.jsonl)
		// are left alone.
		Set<DataType> ours = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Map<String, DataType> parsed : List.of(parser.getComposites(), parser.getEnums(),
			parser.getTypes(), parser.getFunctions())) {
			for (DataType dt : parsed.values()) {
				JsonObject m = meta.get(dt.getName());
				if (m != null && ours.add(dt)) {
					dt.setCategoryPath(new CategoryPath(m.get("category").getAsString()));
					restoreComments(dt, m);
				}
			}
		}
		for (DataType dt : ours) {
			dtm.resolve(dt, DataTypeConflictHandler.REPLACE_HANDLER);
		}
		if (ours.size() != meta.size()) {
			printerr(String.format("types.h: expected %d types, parsed %d", meta.size(),
				ours.size()));
		}
		return ours.size();
	}

	private static void restoreComments(DataType dt, JsonObject meta) {
		if (meta.has("description") && !(dt instanceof TypeDef)) {
			dt.setDescription(meta.get("description").getAsString());
		}
		if (!meta.has("memberComments")) {
			return;
		}
		JsonObject members = meta.getAsJsonObject("memberComments");
		if (dt instanceof Composite composite) {
			for (DataTypeComponent c : composite.getDefinedComponents()) {
				// Same key as ExportAnnotations.fieldKey (scripts are compiled separately).
				String key = c.getFieldName() != null ? c.getFieldName() : c.getDefaultFieldName();
				JsonElement comment = members.get(key);
				if (comment != null) {
					c.setComment(comment.getAsString());
				}
			}
		}
		else if (dt instanceof ghidra.program.model.data.Enum e) {
			for (String name : e.getNames()) {
				JsonElement comment = members.get(name);
				if (comment != null) {
					long value = e.getValue(name);
					e.remove(name);
					e.add(name, value, comment.getAsString());
				}
			}
		}
	}

	private int applyFunctions(Path dir) throws Exception {
		FunctionSignatureParser parser =
			new FunctionSignatureParser(currentProgram.getDataTypeManager(), null);
		int count = 0;
		for (JsonObject o : readJsonl(dir.resolve("functions.jsonl"))) {
			Address addr = toAddr(o.get("addr").getAsString());
			Function f = getFunctionAt(addr);
			if (f == null) {
				f = createFunction(addr, null);
			}
			if (f == null) {
				printerr("Could not create a function at " + addr);
				continue;
			}
			// Name (and namespace) first: for __thiscall the type of `this` comes from the
			// class namespace the function sits in.
			if (o.has("name")) {
				SymbolPath path = new SymbolPath(o.get("name").getAsString());
				f.getSymbol().setNameAndNamespace(path.getName(), namespaceOf(path),
					SourceType.USER_DEFINED);
			}
			if (o.has("signature")) {
				FunctionDefinitionDataType def =
					parser.parse(f.getSignature(), o.get("signature").getAsString());
				String convention = o.get("callingConvention").getAsString();
				if (!convention.equals(Function.UNKNOWN_CALLING_CONVENTION_STRING)) {
					def.setCallingConvention(convention);
				}
				ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(addr, def,
					SourceType.USER_DEFINED, false, FunctionRenameOption.NO_CHANGE);
				if (!cmd.applyTo(currentProgram, monitor)) {
					printerr("Signature at " + addr + ": " + cmd.getStatusMsg());
					continue;
				}
				f.setNoReturn(o.has("noReturn"));
			}
			count++;
		}
		return count;
	}

	private int applyLabels(Path dir) throws Exception {
		SymbolTable symbols = currentProgram.getSymbolTable();
		int count = 0;
		for (JsonObject o : readJsonl(dir.resolve("labels.jsonl"))) {
			Address addr = toAddr(o.get("addr").getAsString());
			SymbolPath path = new SymbolPath(o.get("name").getAsString());
			Namespace ns = namespaceOf(path);
			Symbol s = symbols.getSymbol(path.getName(), addr, ns);
			if (s == null) {
				s = symbols.createLabel(addr, path.getName(), ns, SourceType.USER_DEFINED);
			}
			if (o.get("primary").getAsBoolean()) {
				s.setPrimary();
			}
			count++;
		}
		return count;
	}

	private int applyData(Path dir) throws Exception {
		DataTypeManager dtm = currentProgram.getDataTypeManager();
		int count = 0;
		for (JsonObject o : readJsonl(dir.resolve("data.jsonl"))) {
			Address addr = toAddr(o.get("addr").getAsString());
			String typePath = o.get("type").getAsString();
			DataType dt = findType(dtm, typePath);
			if (dt == null || dt.getLength() <= 0) {
				printerr("Data at " + addr + ": unknown or unsized type " + typePath);
				continue;
			}
			clearListing(addr, addr.add(dt.getLength() - 1));
			createData(addr, dt);
			count++;
		}
		return count;
	}

	private int applyComments(Path dir) throws Exception {
		Listing listing = currentProgram.getListing();
		int count = 0;
		for (JsonObject o : readJsonl(dir.resolve("comments.jsonl"))) {
			listing.setComment(toAddr(o.get("addr").getAsString()),
				CommentType.valueOf(o.get("type").getAsString()), o.get("text").getAsString());
			count++;
		}
		return count;
	}

	private Namespace namespaceOf(SymbolPath path) throws InvalidInputException {
		SymbolPath parent = path.getParent();
		if (parent == null) {
			return currentProgram.getGlobalNamespace();
		}
		return NamespaceUtils.createNamespaceHierarchy(parent.getPath(), null, currentProgram,
			SourceType.USER_DEFINED);
	}

	// Pointer and array types are derived on demand, so they may not exist in the type
	// manager yet; rebuild them from their base type's path.
	private static DataType findType(DataTypeManager dtm, String path) {
		DataType dt = dtm.getDataType(path);
		if (dt != null) {
			return dt;
		}
		if (path.endsWith(" *")) {
			DataType base = findType(dtm, path.substring(0, path.length() - 2));
			return base == null ? null : new PointerDataType(base, dtm);
		}
		Matcher m = ARRAY.matcher(path);
		if (m.matches()) {
			DataType element = findType(dtm, m.group(1) + m.group(3));
			return element == null ? null
					: new ArrayDataType(element, Integer.parseInt(m.group(2)), element.getLength(),
						dtm);
		}
		return null;
	}

	private static List<JsonObject> readJsonl(Path file) throws IOException {
		List<JsonObject> out = new ArrayList<>();
		if (!Files.exists(file)) {
			return out;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				out.add(JsonParser.parseString(line).getAsJsonObject());
			}
		}
		return out;
	}
}
