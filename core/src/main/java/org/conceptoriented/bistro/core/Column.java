package org.conceptoriented.bistro.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class Column implements Element {

    private Schema schema;
    public Schema getSchema() {
        return this.schema;
    }

    private final UUID id;
    public UUID getId() {
        return this.id;
    }

    private String name;
    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }

    private Table input;
    public Table getInput() {
        return this.input;
    }
    public void setInput(Table table) {
        this.input = table;
    }

    private Table output;
    public Table getOutput() {
        return this.output;
    }
    public void setOutput(Table table) { this.output = table; this.setValue(null); }

    //
    // Data (public)
    //

    private ColumnData data;

    public Object getValue(long id) { return this.data.getValue(id); }

    public void setValue(long id, Object value) { this.data.setValue(id, value); this.isDirty = true; }

    public void setValue(Object value) { this.data.setValue(value); this.isDirty = true; }

    public void setValue() { this.data.setValue(); this.isDirty = true; }

    public Object getDefaultValue() { return this.data.getDefaultValue(); }
    public void setDefaultValue(Object value) { this.data.setDefaultValue(value); this.isDirty = true; }

    //
    // Data (protected). These are used from Table only (all columns change their ranges simultaneously) and from users - users add/remove elements via tables.
    //

    protected void add() { this.data.add(); this.isDirty = true; }
    protected void add(long count) { this.data.add(count); this.isDirty = true; }

    protected void remove() { this.data.remove(1); this.isDirty = true; }
    protected void remove(long count) { this.data.remove(count); this.isDirty = true; }

    protected void removeAll() { this.data.removeAll(); this.isDirty = true; }

    //
    // Element interface
    //

    @Override
    public Table getTable() {
        return null;
    }

    @Override
    public Column getColumn() {
        return this;
    }

    @Override
    public List<Element> getDependencies() {
        List<Element> deps = new ArrayList<>();

        if(this.getDefinitionType() == ColumnDefinitionType.NOOP) {
            if(this.isKey() && this.getInput().getDefinitionType() == TableDefinitionType.PROD) {
                deps.add(this.getInput()); // Key-columns depend on the prod-table (if any) because they are filled by their population procedure
            }
        }
        else if(this.definition != null) {
            deps = this.definition.getDependencies();
            if(deps == null) deps = new ArrayList<>();
        }

        return deps;
    }
    @Override
    public boolean hasDependency(Element element) {
        for(Element dep : this.getDependencies()) {
            if(dep == element) return true;
            if(dep.hasDependency(element)) return true; // Recursion
        }
        return false;
    }

    @Override
    public List<Element> getDependants() {
        List<Element> cols = schema.getColumns().stream().filter(x -> x.getDependencies().contains(this)).collect(Collectors.<Element>toList());
        List<Element> tabs = schema.getTables().stream().filter(x -> x.getDependencies().contains(this)).collect(Collectors.<Element>toList());

        List<Element> ret = new ArrayList<>();
        ret.addAll(cols);
        for(Element d : tabs) {
            if(!ret.contains(d)) ret.add(d);
        }

        return ret;
    }
    @Override
    public boolean hasDependant(Element element) {
        for(Element dep : this.getDependants()) {
            if(dep == element) return true;
            if(dep.hasDependant(element)) return true;// Recursion
        }
        return false;
    }

    private List<BistroError> definitionErrors = new ArrayList<>();
    @Override
    public List<BistroError> getDefinitionErrors() { // Empty list in the case of no errors
        List<BistroError> ret = new ArrayList<>();
        ret.addAll(this.definitionErrors);

        if(this.definition != null) {
            ret.addAll(this.definition.getErrors());
        }

        return ret;
    }

    @Override
    public boolean hasDefinitionErrorsDeep() { // Recursively
        if(this.definitionErrors.size() > 0) return true; // Check this element

        // Otherwise check errors in dependencies (recursively)
        for(Element dep : this.getDependencies()) {
            if(dep.hasDefinitionErrorsDeep()) return true;
        }

        return false;
    }

    private List<BistroError> executionErrors = new ArrayList<>();
    @Override
    public List<BistroError> getExecutionErrors() { // Empty list in the case of no errors
        return this.executionErrors;
    }

    @Override
    public boolean hasExecutionErrorsDeep() {
        if(executionErrors.size() > 0) return true; // Check this element

        // Otherwise check errors in dependencies (recursively)
        for(Element dep : this.getDependencies()) {
            if(dep.hasExecutionErrorsDeep()) return true;
        }

        return false;
    }

    private boolean isDirty = false;
    @Override
    public boolean isDirty() {
        return this.isDirty;
    }
    @Override
    public void setDirty() {
        this.isDirty = true;
    }

    @Override
    public boolean isDirtyDeep() {
        if(this.isDirty) return true;

        // Otherwise check if there is a dirty dependency (recursively)
        for(Element dep : this.getDependencies()) {
            if(dep.isDirtyDeep()) return true;
        }

        return false;
    }

    @Override
    public void run() {
        this.eval();
    }

    //
    // Evaluate
    //

    // Evaluate only this individual column if possible
    public void eval() {

        // Skip non-derived columns - they do not participate in evaluation
        if(!this.isDerived()) {

            // Propagate dirty status to all dependants before resting it
            if(this.isDirty()) {
                this.getDependants().forEach(x -> x.setDirty());
            }

            this.isDirty = false;

            return;
        }

        // Clear all evaluation errors before any new evaluation
        this.executionErrors.clear();

        // If there are some definition errors then no possibility to eval (including cycles)
        if(this.hasDefinitionErrorsDeep()) { // this.canEvalute false
            // TODO: Add error: cannot evaluate because of definition error in a dependency
            return;
        }
        // No definition errors - canEvaluate true

        // If everything is up-to-date then there is no need to eval
        if(!this.isDirtyDeep()) { // this.needEvaluate false
            // TODO: Add error: cannot evaluate because of dirty dependency
            return;
        }
        // There exists dirty status - needEvaluate true

        // If there were evaluation errors
        if(this.hasExecutionErrorsDeep()) { // this.canEvaluate false
            // TODO: Add error: cannot evaluate because of execution error in a dependency
            return;
        }
        // No errors while evaluating dependencies

        //
        // Really evaluate using definition
        //
        if(this.definition != null) {
            this.definition.eval();
            this.executionErrors.addAll(this.definition.getErrors());
        }

        if(this.executionErrors.size() == 0) {
            this.isDirty = false; // Clean the state (remove dirty flag)
        }
        else {
            this.isDirty = true; // Evaluation failed
        }
    }

    //
    // Column (definition) kind
    //

    ColumnDefinition definition; // It is instantiated by calc-link-accu methods (or definition errors are added)

    protected ColumnDefinitionType definitionType;
    public ColumnDefinitionType getDefinitionType() {
        return this.definitionType;
    }
    public void setDefinitionType(ColumnDefinitionType definitionType) {
        this.definitionType = definitionType;
        this.definitionErrors.clear();
        this.executionErrors.clear();

        this.definition = null;
        this.key = false;

        this.setDirty();
    }
    public boolean isDerived() {
        if(this.definitionType == ColumnDefinitionType.NOOP) {
            return false;
        }
        return true;
    }

    //
    // Noop column
    //

    // If true, its outputs will be set by the table population procedure during inference for each new instance
    // It is actually part of the noop-column definition (only noop-columns can be keys)
    private boolean key = false;
    public boolean isKey() {
        return this.key;
    }

    public void noop(boolean isKey) {
        this.setDefinitionType(ColumnDefinitionType.NOOP);
        this.key = true;
        // TODO: Error check: some other definitions might become invalid if they depend on the key column status
        //   Either mark this column as having a definition error (so it will skipped), or mark other columns as having definition errors
    }

    //
    // Calcuate column
    //

    public void calc(Evaluator lambda, ColumnPath... paths) {
        this.setDefinitionType(ColumnDefinitionType.CALC);

        this.definition = new ColumnDefinitionCalc(this, lambda, paths);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
            return;
        }
    }

    public void calc(Evaluator lambda, Column... columns) {
        this.setDefinitionType(ColumnDefinitionType.CALC);

        this.definition = new ColumnDefinitionCalc(this, lambda, columns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
            return;
        }
    }

    public void calc(Expression expr) {
        this.setDefinitionType(ColumnDefinitionType.CALC);

        this.definition = new ColumnDefinitionCalc(this, (Expression) expr);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Link column
    //

    public void link(ColumnPath[] valuePaths, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.LINK);

        this.definition = new ColumnDefinitionLink(this, valuePaths, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void link(Column[] valueColumns, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.LINK);

        this.definition = new ColumnDefinitionLink(this, valueColumns, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void link(Expression[] valueExprs, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.LINK);

        this.definition = new ColumnDefinitionLink(this, valueExprs, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Proj column
    //

    public void proj(ColumnPath[] valuePaths, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.PROJ);

        this.definition = new ColumnDefinitionProj(this, valuePaths, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void proj(Column[] valueColumns, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.PROJ);

        this.definition = new ColumnDefinitionProj(this, valueColumns, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void proj(Expression[] valueExprs, Column... keyColumns) {
        this.setDefinitionType(ColumnDefinitionType.PROJ);

        this.definition = new ColumnDefinitionProj(this, valueExprs, keyColumns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Accumulate column
    //

    // Evaluator + parameters OR Expression + no params
    public void accu(ColumnPath groupPath, Evaluator lambda, ColumnPath... paths) {
        this.setDefinitionType(ColumnDefinitionType.ACCU);

        Expression accuExpr;
        if(lambda instanceof Expression)
            accuExpr = (Expression)lambda;
        else
            accuExpr = new Expr(lambda, paths);

        this.definition = new ColumnDefinitionAccu(this, groupPath, accuExpr);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    // Evaluator + parameters OR Expression + no params
    public void accu(Column groupColumn, Evaluator lambda, Column... columns) {
        this.setDefinitionType(ColumnDefinitionType.ACCU);

        Expression accuExpr;
        if(lambda instanceof Expression)
            accuExpr = (Expression)lambda;
        else
            accuExpr = new Expr(lambda, columns);

        this.definition = new ColumnDefinitionAccu(this, new ColumnPath(groupColumn), accuExpr);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Rolling column
    //

    public void roll(int sizePast, int sizeFuture, EvaluatorRoll lambda, ColumnPath... paths) {
        this.setDefinitionType(ColumnDefinitionType.ROLL);

        this.definition = new ColumnDefinitionRoll(this, sizePast, sizeFuture, lambda, paths);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void roll(int sizePast, int sizeFuture, EvaluatorRoll lambda, Column... columns) {
        this.setDefinitionType(ColumnDefinitionType.ROLL);

        this.definition = new ColumnDefinitionRoll(this, sizePast, sizeFuture, lambda, columns);

        if(this.hasDependency(this)) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Serialization and construction
    //

    @Override
    public String toString() {
        return "[" + getName() + "]: " + input.getName() + " -> " + output.getName();
    }

    @Override
    public boolean equals(Object aThat) {
        if (this == aThat) return true;
        if ( !(aThat instanceof Column) ) return false;

        Column that = (Column)aThat;

        if(!that.getId().toString().equals(id.toString())) return false;

        return true;
    }

    public Column(Schema schema, String name, Table input, Table output) {
        this.schema = schema;
        this.id = UUID.randomUUID();
        this.name = name;
        this.input = input;
        this.output = output;

        // Where its output values come from
        this.definitionType = ColumnDefinitionType.NOOP;

        // Where its output values are stored
        this.data = new ColumnData(this.input.getIdRange().start, this.input.getIdRange().end);
    }
}
