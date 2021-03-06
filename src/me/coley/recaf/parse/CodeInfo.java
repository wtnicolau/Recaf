package me.coley.recaf.parse;

import org.objectweb.asm.tree.ClassNode;

import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;

import me.coley.recaf.Input;
import me.coley.recaf.Logging;
import me.coley.recaf.ui.component.DecompileItem.FxDecompile;

public class CodeInfo {
	/**
	 * Class being analyzed.
	 */
	private final ClassNode node;
	/**
	 * Code editor window.
	 */
	private final FxDecompile editor;
	/**
	 * JavaParser compilation result containing all of the analyzed AST.
	 */
	private CompilationUnit cu;
	/**
	 * Utility for mapping regions of text to references.
	 */
	private RegionMapper regions;

	public CodeInfo(ClassNode node, FxDecompile editor) {
		this.node = node;
		this.editor = editor;
	}

	/**
	 * Called to update the {@link #cu compilation unit}'s AST by analyzing the
	 * given code.
	 * 
	 * @param code
	 *            Java to analyze.
	 */
	public void update(String code) {
		ParserConfiguration configuration = new ParserConfiguration();
		JavaParser parser = new JavaParser(configuration);
		ParseResult<CompilationUnit> result = parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(code));
		if (!result.isSuccessful()) {
			Logging.error("Decompilation source parse failed!");
			for (Problem problem : result.getProblems()) {
				Logging.error("\t" + problem.toString());
			}
		}
		// Use generated AST to apply mappings to ranges in the code.
		if (result.getResult().isPresent()) {
			cu = result.getResult().get();
			regions = new RegionMapper(Input.get(), node, cu);
			regions.analyze();
		} else {
			regions = null;
		}
	}

	/**
	 * Adds item to the context menu based on what is currently selected in the text.
	 * 
	 * @param line
	 *            Line position of the caret.
	 * @param column
	 *            Column position of the caret.
	 */
	public void updateContextMenu( int line, int column) {
		ClassNode cn = regions.getClassFromPosition(line, column);
		if (cn != null) {
			editor.updateSelection(cn);
			return;
		}
		MemberNode mn = regions.getMemberFromPosition(line, column);
		if (mn != null) {
			editor.updateSelection(mn);
			return;
		}
		editor.resetSelection();
	}

	/**
	 * @return Status of code region analysis success.
	 */
	public boolean hasRegions() {
		return regions != null;
	}
}
