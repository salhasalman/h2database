/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression.condition;

import java.util.AbstractList;

import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.expression.ValueExpression;
import org.h2.index.IndexCondition;
import org.h2.result.ResultInterface;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueArray;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueNull;

/**
 * A condition with parameter as {@code IN(UNNEST(?))}.
 */
public class ConditionInParameter extends Condition {
    private static final class ParameterList extends AbstractList<Expression> {
        private final Parameter parameter;

        ParameterList(Parameter parameter) {
            this.parameter = parameter;
        }

        @Override
        public Expression get(int index) {
            Value value = parameter.getParamValue();
            if (value instanceof ValueArray) {
                return ValueExpression.get(((ValueArray) value).getList()[index]);
            }
            if (index != 0) {
                throw new IndexOutOfBoundsException();
            }
            return ValueExpression.get(value);
        }

        @Override
        public int size() {
            if (!parameter.isValueSet()) {
                return 0;
            }
            Value value = parameter.getParamValue();
            if (value instanceof ValueArray) {
                return ((ValueArray) value).getList().length;
            }
            return 1;
        }
    }

    private final Database database;

    private Expression left;

    private final Parameter parameter;

    static Value getValue(Database database, Value l, Value value) {
        boolean result = false;
        boolean hasNull = false;
        if (value == ValueNull.INSTANCE) {
            hasNull = true;
        } else if (value.getType() == Value.RESULT_SET) {
            for (ResultInterface ri = value.getResult(); ri.next();) {
                Value r = ri.currentRow()[0];
                if (r == ValueNull.INSTANCE) {
                    hasNull = true;
                } else {
                    result = Comparison.compareNotNull(database, l, r, Comparison.EQUAL);
                    if (result) {
                        break;
                    }
                }
            }
        } else {
            for (Value r : ((ValueArray) value.convertTo(Value.ARRAY)).getList()) {
                if (r == ValueNull.INSTANCE) {
                    hasNull = true;
                } else {
                    result = Comparison.compareNotNull(database, l, r, Comparison.EQUAL);
                    if (result) {
                        break;
                    }
                }
            }
        }
        if (!result && hasNull) {
            return ValueNull.INSTANCE;
        }
        return ValueBoolean.get(result);
    }

    /**
     * Create a new {@code IN(UNNEST(?))} condition.
     *
     * @param database
     *            the database
     * @param left
     *            the expression before {@code IN(UNNEST(?))}
     * @param parameter
     *            parameter
     */
    public ConditionInParameter(Database database, Expression left, Parameter parameter) {
        this.database = database;
        this.left = left;
        this.parameter = parameter;
    }

    @Override
    public Value getValue(Session session) {
        Value l = left.getValue(session);
        if (l == ValueNull.INSTANCE) {
            return l;
        }
        return getValue(database, l, parameter.getValue(session));
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level, int state) {
        left.mapColumns(resolver, level, state);
    }

    @Override
    public Expression optimize(Session session) {
        left = left.optimize(session);
        if (left == ValueExpression.getNull()) {
            return left;
        }
        return this;
    }

    @Override
    public void createIndexConditions(Session session, TableFilter filter) {
        if (!(left instanceof ExpressionColumn)) {
            return;
        }
        ExpressionColumn l = (ExpressionColumn) left;
        if (filter != l.getTableFilter()) {
            return;
        }
        filter.addIndexCondition(IndexCondition.getInList(l, new ParameterList(parameter)));
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
    }

    @Override
    public StringBuilder getSQL(StringBuilder builder) {
        builder.append('(');
        left.getSQL(builder).append(" IN(UNNEST(");
        return parameter.getSQL(builder).append(")))");
    }

    @Override
    public void updateAggregate(Session session, int stage) {
        left.updateAggregate(session, stage);
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && parameter.isEverything(visitor);
    }

    @Override
    public int getCost() {
        return left.getCost();
    }

}
