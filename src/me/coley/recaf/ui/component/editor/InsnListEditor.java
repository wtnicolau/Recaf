package me.coley.recaf.ui.component.editor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.controlsfx.control.PropertySheet;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import me.coley.event.Bus;
import me.coley.event.Listener;
import me.coley.recaf.Input;
import me.coley.recaf.Logging;
import me.coley.recaf.bytecode.AccessFlag;
import me.coley.recaf.bytecode.Asm;
import me.coley.recaf.bytecode.OpcodeUtil;
import me.coley.recaf.bytecode.analysis.Verify;
import me.coley.recaf.bytecode.analysis.Verify.VerifyResults;
import me.coley.recaf.bytecode.search.Parameter;
import me.coley.recaf.config.impl.ConfASM;
import me.coley.recaf.config.impl.ConfBlocks;
import me.coley.recaf.config.impl.ConfKeybinds;
import me.coley.recaf.event.ClassDirtyEvent;
import me.coley.recaf.event.ClassOpenEvent;
import me.coley.recaf.event.ClassRenameEvent;
import me.coley.recaf.event.FieldOpenEvent;
import me.coley.recaf.event.HistoryRevertEvent;
import me.coley.recaf.event.MethodOpenEvent;
import me.coley.recaf.ui.FormatFactory;
import me.coley.recaf.ui.FxSearch;
import me.coley.recaf.ui.component.ActionMenuItem;
import me.coley.recaf.ui.component.BlockPane;
import me.coley.recaf.ui.component.InsnInserter;
import me.coley.recaf.ui.component.OpcodeHBox;
import me.coley.recaf.ui.component.ReflectiveOpcodeSheet;
import me.coley.recaf.ui.component.StackWatcher;
import me.coley.recaf.util.Clipboard;
import me.coley.recaf.util.JavaFX;
import me.coley.recaf.util.Lang;
import me.coley.recaf.util.ScreenUtil;
import me.coley.recaf.util.Threads;

/**
 * Editor for method instructions.
 * 
 * @author Matt
 */
public class InsnListEditor extends BorderPane {
	private final OpcodeList opcodes;
	private final ClassNode owner;
	private final MethodNode method;
	/**
	 * Used by inner class to construct items that depend on the InsnListEditor
	 * class.
	 */
	private final InsnListEditor reference = this;

	public InsnListEditor(ClassNode owner, MethodNode method) {
		this.owner = owner;
		this.method = method;
		this.opcodes = new OpcodeList(method.instructions);
		setCenter(opcodes);
		checkVerify();
	}

	public InsnListEditor(ClassNode owner, MethodNode method, AbstractInsnNode insn) {
		this(owner, method);
	}

	public OpcodeList getOpcodeList() {
		return opcodes;
	}

	public ClassNode getClassNode() {
		return owner;
	}

	public MethodNode getMethod() {
		return method;
	}

	@Listener
	private void onClassDirty(ClassDirtyEvent event) {
		if (event.getNode() == owner) {
			checkVerify();
		}
	}

	@Listener
	private void onClassRevert(HistoryRevertEvent event) {
		// This code has become irrelevant, so it should be closed to prevent
		// possible confusion.
		if (event.getName().equals(owner.name)) {
			getScene().getWindow().hide();
		}
	}

	@Listener
	private void onClassRename(ClassRenameEvent event) {
		// This code has become irrelevant, so it should be closed to prevent
		// possible confusion.
		if (event.getOriginalName().equals(owner.name)) {
			getScene().getWindow().hide();
		}
	}

	private void checkVerify() {
		if (ConfASM.instance().doVerify()) {
			VerifyResults res = Verify.checkValid(owner.name, method);
			if (res.valid()) {
				if (!opcodes.getStyleClass().contains("verify-pass")) {
					opcodes.getStyleClass().add("verify-pass");
				}
				opcodes.getStyleClass().remove("verify-fail");
			} else {
				if (!opcodes.getStyleClass().contains("verify-fail")) {
					opcodes.getStyleClass().add("verify-fail");
				}
				opcodes.getStyleClass().remove("verify-pass");
			}
			opcodes.updateVerification(res);
		}
	}

	/**
	 * Opcode list wrapper.
	 * 
	 * @author Matt
	 */
	public class OpcodeList extends ListView<AbstractInsnNode> {
		/**
		 * Work-around for being unable to style ListView's cells. Instead a
		 * cache of the HBox's of the opcodes is maintained.
		 */
		private final Map<AbstractInsnNode, OpcodeHBox> nodeLookup = new LinkedHashMap<>();
		/**
		 * Method opcode list. Updated when ListView fires change events.
		 */
		private final InsnList instructions;
		/**
		 * Last verification results.
		 */
		private VerifyResults verif;

		public OpcodeList(InsnList instructions) {
			this.instructions = instructions;
			setupModel();
		}

		private void setupModel() {
			OpcodeList list = this;
			getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
			getItems().addAll(instructions.toArray());
			// If I read the docs right, changes are ranges without breaks.
			// If you have opcodes [A-Z] removing ABC+XYZ will make two changes.
			getItems().addListener((ListChangeListener.Change<? extends AbstractInsnNode> c) -> {
				while (c.next()) {
					if (c.wasRemoved()) {
						onRemove(c.getRemoved());
					} else if (c.wasAdded()) {
						onAdd(c.getFrom(), c.getAddedSubList());
					}
					// Update checks *should not* be required due to the
					// reflective nature of how they are edited + instance
					// sharing between InsnList and this ListView.
				}
				// update size after potential non-updating removals
				setSize(getItems().size());
			});
			getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
				onSelect(getSelectionModel().getSelectedItems());
			});
			// delete key to remove items
			setOnKeyPressed(event -> {
				if (event.getCode().equals(KeyCode.DELETE)) {
					getItems().removeAll(selectionModelProperty().getValue().getSelectedItems());
				}
			});
			// Keybinds for copy/paste
			addEventHandler(KeyEvent.KEY_RELEASED, e -> {
				// Only continue of control is held
				ConfKeybinds keys = ConfKeybinds.instance();
				if (keys.active && !e.isControlDown()) {
					return;
				}
				String code = e.getCode().getName();
				if (code.equals(keys.copy.toUpperCase())) {
					// Copy list... don't want to store the observable one
					List<AbstractInsnNode> clone = new ArrayList<>(getSelectionModel().getSelectedItems());
					String key = FormatFactory.opcodeCollectionString(clone, method);
					Clipboard.setContent(key, clone);
				} else if (code.equals(keys.paste.toUpperCase())) {
					// Don't bother if recent copy-value isn't a list
					if (!Clipboard.isRecentType(List.class)) return;
					// Clone because ASM nodes are linked lists...
					// - Can't have those shared refs across multiple methods
					List<AbstractInsnNode> clone = ConfBlocks.createClone(Clipboard.getRecent());
					if (clone == null) return;
					// Insert into list
					int index = getSelectionModel().getSelectedIndex();
					if (index == -1) {
						return;
					}
					if (index < getItems().size() - 1) {
						// Add after selection
						getItems().addAll(index + 1, clone);
					} else {
						// Add to end
						getItems().addAll(clone);
					}
				}
			});
			// Create format entry for opcodes.
			setCellFactory(cell -> new ListCell<AbstractInsnNode>() {
				@Override
				protected void updateItem(AbstractInsnNode node, boolean empty) {
					super.updateItem(node, empty);
					if (empty || node == null) {
						setGraphic(null);
					} else {
						OpcodeHBox box = nodeLookup.get(node);
						BorderPane bp = new BorderPane(box);
						if (verif != null && node == verif.getCause()) {
							String msg = verif.ex.getMessage().split("\n")[0];
							Label lbl = new Label(msg);
							bp.getStyleClass().add("op-verif-fail");
							bp.setRight(lbl);
						}
						setGraphic(bp);
						// wrapped in a mouse-click event so they're generated
						// when clicked.
						// without the wrapper, the selection cannot be known.
						setOnMouseClicked(e -> {
							setContextMenu(createMenu(e, node));
						});
					}
				}

				private ContextMenu createMenu(MouseEvent e, AbstractInsnNode node) {
					// context menu
					ContextMenu ctx = new ContextMenu();
					ctx.getItems().add(new ActionMenuItem(Lang.get("misc.edit"), () -> {
						showOpcodeEditor(node);
					}));
					ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.stackhelper"), () -> {
						StackWatcher stack = new StackWatcher(owner, method);
						getSelectionModel().selectedIndexProperty().addListener(stack);
						getItems().addListener(stack);
						stack.update();
						stack.select(getSelectionModel().getSelectedIndex());
						stack.show();
					}));
					if (node.getPrevious() != null) {
						ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.move.up"), () -> {
							Asm.shiftUp(instructions, getSelectionModel().getSelectedItems());
							Bus.post(new ClassDirtyEvent(owner));
							refreshList();
							sortList();
						}));
					}
					if (node.getNext() != null) {
						ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.move.down"), () -> {
							Asm.shiftDown(instructions, getSelectionModel().getSelectedItems());
							Bus.post(new ClassDirtyEvent(owner));
							refreshList();
							sortList();
						}));
					}
					// default action to first context menu item (edit)
					if ((e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) || (e
							.getButton() == MouseButton.MIDDLE)) {
						showOpcodeEditor(node);
					}
					if (getSelectionModel().getSelectedItems().size() == 1) {
						Input in = Input.get();
						// type-specific options
						switch (node.getType()) {
						case AbstractInsnNode.FIELD_INSN:
							// open definition
							// search references
							FieldInsnNode fin = (FieldInsnNode) node;
							if (in.classes.contains(fin.owner)) {
								ClassNode fOwner = in.getClass(fin.owner);
								FieldNode field = getField(fOwner, fin);
								if (field != null) {
									ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.define"), () -> {
										Bus.post(new FieldOpenEvent(fOwner, field, list));
									}));
								}
							}
							ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.search"), () -> {
								FxSearch.open(Parameter.references(fin.owner, fin.name, fin.desc));
							}));
							break;
						case AbstractInsnNode.METHOD_INSN:
							// open definition
							// search references
							MethodInsnNode min = (MethodInsnNode) node;
							if (in.classes.contains(min.owner)) {
								ClassNode mOwner = in.getClass(min.owner);
								MethodNode method = getMethod(mOwner, min);
								if (method != null) {
									ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.define"), () -> {
										Bus.post(new MethodOpenEvent(mOwner, method, list));
									}));
								}
							}
							ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.search"), () -> {
								FxSearch.open(Parameter.references(min.owner, min.name, min.desc));
							}));
							break;
						case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
							// open definition
							// search references
							InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) node;
							if (indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof Handle) {
								Handle h = (Handle) indy.bsmArgs[1];
								if (in.classes.contains(h.getOwner())) {
									ClassNode mOwner = in.getClass(h.getOwner());
									MethodNode method = getMethod(mOwner, h);
									if (mOwner != null && method != null) {
										ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.define"), () -> {
											Bus.post(new MethodOpenEvent(mOwner, method, list));
										}));
									}
								}
								ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.search"), () -> {
									FxSearch.open(Parameter.references(h.getOwner(), h.getName(), h.getDesc()));
								}));
							}

							break;
						case AbstractInsnNode.TYPE_INSN:
							// open definition
							// search references
							TypeInsnNode tin = (TypeInsnNode) node;
							if (in.classes.contains(tin.desc)) {
								ClassNode tOwner = in.getClass(tin.desc);
								if (tOwner != null) {
									ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.define"), () -> {
										Bus.post(new ClassOpenEvent(tOwner));
									}));
								}
								ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.search"), () -> {
									FxSearch.open(Parameter.references(tin.desc, null, null));
								}));
							}
							break;
						}
					} else {
						ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.block.save"), () -> {
							showBlockSave();
						}));
					}
					// insert block
					ctx.getItems().add(new ActionMenuItem(Lang.get("ui.edit.method.block.load"), () -> {
						showBlockLoad(node);
					}));
					// insert opcode
					ctx.getItems().add(new ActionMenuItem(Lang.get("misc.add"), () -> {
						showOpcodeInserter(node);
					}));
					// remove opcode
					ctx.getItems().add(new ActionMenuItem(Lang.get("misc.remove"), () -> {
						getItems().removeAll(getSelectionModel().getSelectedItems());
					}));
					return ctx;
				}

				private void sortList() {
					// Why would we need to sort this list by index?
					// Because removing and re-insertion throws exceptions that
					// I don't know how to resolve.
					// So this is the "temporary" fix that probably isn't
					// "temporary" at all.
					getItems().sort(new Comparator<AbstractInsnNode>() {
						@Override
						public int compare(AbstractInsnNode o1, AbstractInsnNode o2) {
							int i1 = instructions.indexOf(o1);
							int i2 = instructions.indexOf(o2);
							return Integer.compare(i1, i2);
						}
					});
				}

				private void showOpcodeEditor(AbstractInsnNode node) {
					InsnEditor x = new InsnEditor(reference, node);
					String t = Lang.get("misc.edit") + ":" + node.getClass().getSimpleName();
					Scene sc = JavaFX.scene(x, 500, 300);
					Stage st = JavaFX.stage(sc, t, true);
					st.setOnCloseRequest(a -> refreshItem(node));
					st.show();
				}

				private void showOpcodeInserter(AbstractInsnNode node) {
					// Opcode type select
					// ---> specific values
					// before / after insertion point
					InsnInserter x = new InsnInserter(reference, node);
					String t = Lang.get("ui.edit.method.insert.title");
					Scene sc = JavaFX.scene(x, ScreenUtil.prefWidth() - 100, 400);
					Stage st = JavaFX.stage(sc, t, true);
					st.setOnCloseRequest(a -> refresh());
					st.show();
				}

				private void showBlockSave() {
					BlockPane.Saver x = new BlockPane.Saver(getSelectionModel().getSelectedItems(), reference.getMethod());
					String t = Lang.get("ui.edit.method.block.title");
					Scene sc = JavaFX.scene(x, ScreenUtil.prefWidth() - 100, 420);
					Stage st = JavaFX.stage(sc, t, true);
					st.show();
				}

				private void showBlockLoad(AbstractInsnNode node) {
					BlockPane.Inserter x = new BlockPane.Inserter(getSelectionModel().getSelectedItem(), reference);
					String t = Lang.get("ui.edit.method.block.title");
					Scene sc = JavaFX.scene(x, ScreenUtil.prefWidth() - 100, 460);
					Stage st = JavaFX.stage(sc, t, true);
					st.show();
				}

				private FieldNode getField(ClassNode owner, FieldInsnNode fin) {
					Optional<FieldNode> opt = owner.fields.stream().filter(f -> f.name.equals(fin.name) && f.desc.equals(
							fin.desc)).findAny();
					return opt.isPresent() ? opt.get() : null;
				}

				private MethodNode getMethod(ClassNode owner, MethodInsnNode min) {
					Optional<MethodNode> opt = owner.methods.stream().filter(m -> m.name.equals(min.name) && m.desc.equals(
							min.desc)).findAny();
					return opt.isPresent() ? opt.get() : null;
				}

				private MethodNode getMethod(ClassNode owner, Handle h) {
					Optional<MethodNode> opt = owner.methods.stream().filter(m -> m.name.equals(h.getName()) && m.desc.equals(h
							.getDesc())).findAny();
					return opt.isPresent() ? opt.get() : null;
				}
			});
			//
			if (getItems().size() == 0) {
				// Hack to allow insertion of an initial opcode.
				ContextMenu ctx = new ContextMenu();
				ctx.getItems().add(new ActionMenuItem(Lang.get("misc.add"), () -> {
					// Add basic starter instructions
					LabelNode start = new LabelNode();
					InsnNode ret = new InsnNode(Opcodes.RETURN);
					LabelNode end = new LabelNode();
					// Add 'this'
					if (method.localVariables == null) {
						method.localVariables = new ArrayList<>();
					}
					if (method.localVariables.size() == 0 && !AccessFlag.isStatic(method.access)) {
						method.localVariables.add(new LocalVariableNode("this", "L" + owner.name + ";", null, start, end, 0));
						method.maxLocals = 1;
					}
					getItems().addAll(start, ret, end);
					setContextMenu(null);
					refreshList();
				}));
				setContextMenu(ctx);
			}
			// add opcodes to item list
			refreshList();
		}

		private void onAdd(int start, List<? extends AbstractInsnNode> added) {
			// Create InsnList to add to existing list.
			InsnList insn = new InsnList();
			added.forEach(ain -> {
				createFormat(ain);
				insn.add(ain);
			});
			// start marks the beginning of the range of added opcodes.
			if (start == 0) {
				// insert to start of list
				instructions.insert(insn);
			} else {
				// get opcode[start -1] and add the opcodes after it
				// This puts them in the intended start location.
				AbstractInsnNode location = instructions.get(start - 1);
				instructions.insert(location, insn);
			}
			refreshList();
			Bus.post(new ClassDirtyEvent(owner));
		}

		private void onRemove(List<? extends AbstractInsnNode> removed) {
			// remove from lookup cache
			removed.forEach(ain -> nodeLookup.remove(ain));
			// remove from instructions linked list
			if (removed.size() > 1) {
				AbstractInsnNode insnStart = removed.get(0);
				AbstractInsnNode insnEnd = removed.get(removed.size() - 1);
				OpcodeUtil.link(method.instructions, insnStart, insnEnd);
			} else {
				instructions.remove(removed.get(0));
			}
			refreshList();
			Bus.post(new ClassDirtyEvent(owner));
		}

		private void onSelect(ObservableList<AbstractInsnNode> selected) {
			List<OpcodeHBox> list = new ArrayList<>();
			for (AbstractInsnNode ain : instructions.toArray()) {
				OpcodeHBox box = nodeLookup.get(ain);
				if (box != null) {
					list.add(box);
				}
			}
			list.forEach(cell -> {
				cell.getStyleClass().remove("op-selected");
			});
			selected.forEach(ain -> {
				updateReferenced(ain, list);
				mark(ain, list, "op-selected");
			});
		}

		/**
		 * Marks referenced opcodes with a custom style class.
		 * 
		 * @param ain
		 * @param list
		 */
		private void updateReferenced(AbstractInsnNode ain, List<OpcodeHBox> list) {
			list.forEach(cell -> {
				cell.getStyleClass().remove("op-jumpdest");
				cell.getStyleClass().remove("op-jumpdest-fail");
				cell.getStyleClass().remove("op-jumpdest-reverse");
				cell.getStyleClass().remove("op-varmatch");
			});
			if (ain == null) {
				return;
			}
			switch (ain.getType()) {
			case AbstractInsnNode.JUMP_INSN:
				mark(ain.getNext(), list, "op-jumpdest-fail");
				mark(((JumpInsnNode) ain).label, list, "op-jumpdest");
				break;
			case AbstractInsnNode.LOOKUPSWITCH_INSN:
				LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) ain;
				mark(lsin.dflt, list, "op-jumpdest-fail");
				// TODO: Show associated keys to destinations
				lsin.labels.forEach(l -> mark(l, list, "op-jumpdest"));
				break;
			case AbstractInsnNode.TABLESWITCH_INSN:
				TableSwitchInsnNode tsin = (TableSwitchInsnNode) ain;
				// TODO: Show associated keys to destinations
				mark(tsin.dflt, list, "op-jumpdest-fail");
				tsin.labels.forEach(l -> mark(l, list, "op-jumpdest"));
				break;
			case AbstractInsnNode.VAR_INSN: {
				int var = ((VarInsnNode) ain).var;
				// Show other opcodes that modify the variable
				for (AbstractInsnNode insn : getItems()) {
					if (insn.getType() == AbstractInsnNode.VAR_INSN && ((VarInsnNode) insn).var == var) {
						mark(insn, list, "op-varmatch");
					} else if (insn.getType() == AbstractInsnNode.IINC_INSN && ((IincInsnNode) insn).var == var) {
						mark(insn, list, "op-varmatch");
					}
				}
				break;
			}
			case AbstractInsnNode.IINC_INSN: {
				int var = ((IincInsnNode) ain).var;
				// Show other opcodes that modify the variable
				for (AbstractInsnNode insn : getItems()) {
					if (insn.getType() == AbstractInsnNode.VAR_INSN && ((VarInsnNode) insn).var == var) {
						mark(insn, list, "op-varmatch");
					} else if (insn.getType() == AbstractInsnNode.IINC_INSN && ((IincInsnNode) insn).var == var) {
						mark(insn, list, "op-varmatch");
					}
				}
				break;
			}
			case AbstractInsnNode.FIELD_INSN:
				FieldInsnNode fin = (FieldInsnNode) ain;
				// Show other opcodes that modify the field
				for (AbstractInsnNode insn : getItems()) {
					if (insn.getType() == AbstractInsnNode.FIELD_INSN) {
						FieldInsnNode other = ((FieldInsnNode) insn);
						if (fin.name.equals(other.name) && fin.desc.equals(other.desc) && fin.owner.equals(other.owner)) {
							mark(insn, list, "op-varmatch");
						}
					}
				}
				break;
			case AbstractInsnNode.LINE:
				LineNumberNode lin = (LineNumberNode) ain;
				mark(lin.start, list, "op-jumpdest");
				break;
			case AbstractInsnNode.LABEL:
				// reverse lookup
				for (AbstractInsnNode insn : getItems()) {
					if (insn.getType() == AbstractInsnNode.JUMP_INSN) {
						JumpInsnNode other = (JumpInsnNode) insn;
						if (other.label.equals(ain)) {
							mark(insn, list, "op-jumpdest-reverse");
						}
					} else if (insn.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN) {
						LookupSwitchInsnNode other = (LookupSwitchInsnNode) insn;
						for (LabelNode ln : other.labels) {
							if (ln.equals(insn)) {
								mark(insn, list, "op-jumpdest-reverse");
							}
						}
					} else if (insn.getType() == AbstractInsnNode.TABLESWITCH_INSN) {
						TableSwitchInsnNode other = (TableSwitchInsnNode) insn;
						for (LabelNode ln : other.labels) {
							if (ln.equals(insn)) {
								mark(insn, list, "op-jumpdest-reverse");
							}
						}
					} else if (insn.getType() == AbstractInsnNode.LINE) {
						LineNumberNode line = (LineNumberNode) insn;
						if (line.start.equals(ain)) {
							mark(insn, list, "op-jumpdest-reverse");
						}
					}
				}
				break;
			}

		}

		/**
		 * Applies the given class to the cell at the index matching the
		 * opcode's index in the items property.
		 * 
		 * @param ain
		 *            Opcode to mark.
		 * @param list
		 *            List of cells.
		 * @param clazz
		 *            Class to apply to cell.
		 */
		private void mark(AbstractInsnNode ain, List<OpcodeHBox> list, String clazz) {
			int index = getItems().indexOf(ain);
			if (index >= 0 && index < list.size()) {
				// this automatically refreshes the node too, so the style
				// should be instantly applied
				list.get(index).getStyleClass().add(clazz);
			} else {
				Logging.error("Could not locate: " + ain + " @" + index + " with " + clazz);
			}
		}

		/**
		 * Sets the InsnList size through reflection. This is done to ensure
		 * cuts done via reflection are accounted for in the InsnList structure.
		 * 
		 * @param size
		 *            New method instructions size.
		 */
		private void setSize(int size) {
			try {
				Field f = InsnList.class.getDeclaredField("size");
				f.setAccessible(true);
				f.setInt(method.instructions, size);
			} catch (Exception e) {
				Logging.error(e);
			}
		}

		/**
		 * Update CSS and fire update for item that caused failiure, if there
		 * was a failure at all.
		 * 
		 * @param verif
		 *            Verification results.
		 */
		public void updateVerification(VerifyResults verif) {
			this.verif = verif;
			AbstractInsnNode cause = verif.getCause();
			// Ensure there are values in nodeLookup.
			if (nodeLookup.size() == 0) {
				refreshList();
			}
			Threads.runFx(() -> {
				// create list of HBoxes
				List<OpcodeHBox> list = new ArrayList<>();
				for (AbstractInsnNode ain : instructions.toArray()) {
					OpcodeHBox box = nodeLookup.get(ain);
					if (box != null) {
						list.add(box);
					}
				}
				if (cause != null && getItems().contains(cause)) {
					// add failure style
					// mark(cause, list, "op-verif-fail");
					refreshItem(cause);
				} else if (cause == null) {
					// remove failure style
					list.forEach(cell -> {
						cell.getStyleClass().remove("op-verif-fail");
					});
				}
			});
		}

		/**
		 * Recreates the opcode representations.
		 */
		public void refreshList() {
			Threads.runFx(() -> {
				// regenerate opcode representations
				for (AbstractInsnNode ain : instructions.toArray()) {
					createFormat(ain);
				}
				// update visible cells
				refresh();
			});
		}

		/**
		 * Recreate the opcode representation for the given opcode.
		 * 
		 * @param ain
		 *            Opcode to refresh.
		 */
		public void refreshItem(AbstractInsnNode ain) {
			Threads.runFx(() -> {
				createFormat(ain);
				refresh();
			});
		}

		/**
		 * Create an HBox representation of the given opcode, put it into the
		 * {@link #nodeLookup} map.
		 * 
		 * @param ain
		 */
		private void createFormat(AbstractInsnNode ain) {
			nodeLookup.put(ain, FormatFactory.opcode(ain, method));
		}

		public class InsnEditor extends BorderPane {
			public InsnEditor(InsnListEditor list, AbstractInsnNode ain) {
				PropertySheet propertySheet = new ReflectiveOpcodeSheet(list, ain);
				VBox.setVgrow(propertySheet, Priority.ALWAYS);
				setCenter(propertySheet);
			}
		}
	}
}
