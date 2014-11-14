package com.kylinolap.storage.filter;

import java.util.List;

import com.kylinolap.dict.Dictionary;
import com.kylinolap.metadata.model.cube.TblColRef;

import it.uniroma3.mat.extendedset.intset.ConciseSet;

/**
 * @author yangli9
 *
 * Evaluate a group of records against a filter in batch.
 */
public class BitMapFilterEvaluator {

    /** Provides bitmaps for a record group ranging [0..N-1], where N is the size of the group */
    public static interface BitMapProvider {
        
        /** return records whose specified column having specified value */
        ConciseSet getBitMap(TblColRef col, int valueId);
        
        /** return size of the group */
        int getRecordsCount();
    }
    
    BitMapProvider provider;
    
    public BitMapFilterEvaluator(BitMapProvider bitMapProvider) {
        this.provider = bitMapProvider;
    }
    
    /**
     * @param filter
     * @return a set of records that match the filter; or null if filter is null or unable to evaluate
     */
    public ConciseSet evaluate(TupleFilter filter) {
        if (filter == null)
            return null;
        
        if (filter instanceof LogicalTupleFilter)
            return evalLogical((LogicalTupleFilter) filter);
        
        if (filter instanceof CompareTupleFilter)
            return evalCompare((CompareTupleFilter) filter);
        
        return null; // unable to evaluate
    }

    private ConciseSet evalCompare(CompareTupleFilter filter) {
        switch (filter.getOperator()) {
        case ISNULL:
            return evalCompareIsNull(filter);
        case ISNOTNULL:
            return evalCompareIsNotNull(filter);
        case EQ:
            return evalCompareEqual(filter);
        case NEQ:
            return evalCompareNotEqual(filter);
        case IN:
            return evalCompareIn(filter);
        case NOTIN:
            return evalCompareNotIn(filter);
        case LT:
            return evalCompareLT(filter);
        case LTE:
            return evalCompareLTE(filter);
        case GT:
            return evalCompareGT(filter);
        case GTE:
            return evalCompareGTE(filter);
        default:
            throw new IllegalStateException("Unsupported operator " + filter.getOperator());
        }
    }

    private ConciseSet evalCompareLT(CompareTupleFilter filter) {
        int id = Dictionary.stringToDictId(filter.getFirstValue());
        return collectRange(filter.getColumn(), 0, id);
    }

    private ConciseSet evalCompareLTE(CompareTupleFilter filter) {
        int id = Dictionary.stringToDictId(filter.getFirstValue());
        return collectRange(filter.getColumn(), 0, id + 1);
    }

    private ConciseSet evalCompareGT(CompareTupleFilter filter) {
        int id = Dictionary.stringToDictId(filter.getFirstValue());
        return collectRange(filter.getColumn(), id + 1, provider.getRecordsCount());
    }

    private ConciseSet evalCompareGTE(CompareTupleFilter filter) {
        int id = Dictionary.stringToDictId(filter.getFirstValue());
        return collectRange(filter.getColumn(), id, provider.getRecordsCount());
    }

    private ConciseSet collectRange(TblColRef column, int from, int toExclusive) {
        ConciseSet set = new ConciseSet();
        for (int i = from; i < toExclusive; i++) {
            set.addAll(provider.getBitMap(column, i));
        }
        return set;
    }

    private ConciseSet evalCompareEqual(CompareTupleFilter filter) {
        int id = Dictionary.stringToDictId(filter.getFirstValue());
        return provider.getBitMap(filter.getColumn(), id).clone(); // NOTE the clone() to void messing provider's cache
    }
    
    private ConciseSet evalCompareNotEqual(CompareTupleFilter filter) {
        ConciseSet set = evalCompareEqual(filter);
        not(set);
        dropNull(set, filter);
        return set;
    }

    private ConciseSet evalCompareIn(CompareTupleFilter filter) {
        ConciseSet set = new ConciseSet();
        for (String value : filter.getValues()) {
            int id = Dictionary.stringToDictId(value);
            set.addAll(provider.getBitMap(filter.getColumn(), id));
        }
        return set;
    }

    private ConciseSet evalCompareNotIn(CompareTupleFilter filter) {
        ConciseSet set = evalCompareIn(filter);
        not(set);
        dropNull(set, filter);
        return set;
    }

    private void dropNull(ConciseSet set, CompareTupleFilter filter) {
        ConciseSet nullSet = evalCompareIsNull(filter);
        set.removeAll(nullSet);
    }

    private ConciseSet evalCompareIsNull(CompareTupleFilter filter) {
        int nullId = Dictionary.stringToDictId(filter.getNullString());
        return provider.getBitMap(filter.getColumn(), nullId).clone(); // NOTE the clone() to void messing provider's cache
    }

    private ConciseSet evalCompareIsNotNull(CompareTupleFilter filter) {
        ConciseSet set = evalCompareIsNull(filter);
        not(set);
        return set;
    }

    private ConciseSet evalLogical(LogicalTupleFilter filter) {
        List<? extends TupleFilter> children = filter.getChildren();
        
        switch (filter.getOperator()) {
        case AND:
            return evalLogicalAnd(children);
        case OR:
            return evalLogicalOr(children);
        case NOT:
            return evalLogicalNot(children);
        default:
            throw new IllegalStateException("Unsupported operator " + filter.getOperator());
        }
    }

    private ConciseSet evalLogicalAnd(List<? extends TupleFilter> children) {
        ConciseSet set = new ConciseSet();
        not(set);
        
        for (TupleFilter c : children) {
            ConciseSet t = evaluate(c);
            if (t == null)
                continue; // because it's AND
            
            set.retainAll(t);
        }
        return set;
    }

    private ConciseSet evalLogicalOr(List<? extends TupleFilter> children) {
        ConciseSet set = new ConciseSet();
        
        for (TupleFilter c : children) {
            ConciseSet t = evaluate(c);
            if (t == null)
                return null; // because it's OR
            
            set.addAll(t);
        }
        return set;
    }

    private ConciseSet evalLogicalNot(List<? extends TupleFilter> children) {
        ConciseSet set = evaluate(children.get(0));
        if (set == null)
            return null;
        
        not(set);
        return set;
    }

    private void not(ConciseSet set) {
        set.add(provider.getRecordsCount());
        set.complement();
    }

    public static void main(String[] args) {
        ConciseSet s = new ConciseSet();
        s.add(5);
        s.complement();
        System.out.println(s);
    }
}
