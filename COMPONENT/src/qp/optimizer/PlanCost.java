/**
 * This method calculates the cost of the generated plans
 * also estimates the statistics of the result relation
 **/

package qp.optimizer;

import qp.operators.*;
import qp.utils.Attribute;
import qp.utils.Batch;
import qp.utils.Condition;
import qp.utils.Schema;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.StringTokenizer;

public class PlanCost {

    long cost;
    long numtuple;

    /**
     * If buffers are not enough for a selected join
     * * then this plan is not feasible and return
     * * a cost of infinity
     **/
    boolean isFeasible;

    /**
     * Hashtable stores mapping from Attribute name to
     * * number of distinct values of that attribute
     **/
    HashMap<Attribute, Long> ht;


    public PlanCost() {
        ht = new HashMap<>();
        cost = 0;
    }

    /**
     * Returns the cost of the plan
     **/
    public long getCost(Operator root) {
        cost = 0;
        isFeasible = true;
        numtuple = calculateCost(root);
        if (isFeasible) {
            return cost;
        } else {
            System.out.println("notFeasible");
            return Long.MAX_VALUE;
        }
    }

    /**
     * Get number of tuples in estimated results
     **/
    public long getNumTuples() {
        return numtuple;
    }


    /**
     * Returns number of tuples in the root
     **/
    protected long calculateCost(Operator node) {
        if (node.getOpType() == OpType.JOIN) {
            return getStatistics((Join) node);
        } else if (node.getOpType() == OpType.SELECT) {
            return getStatistics((Select) node);
        } else if (node.getOpType() == OpType.AGGREGATE) {
            return getStatistics((Aggregate) node);
        } else if (node.getOpType() == OpType.PROJECT) {
            return getStatistics((Project) node);
        } else if (node.getOpType() == OpType.SCAN) {
            return getStatistics((Scan) node);
        } else if (node.getOpType() == OpType.DISTINCT) {
            return getStatistics((Distinct) node);
        } else if (node.getOpType() == OpType.ORDER_BY) {
            return getStatistics((OrderBy) node);
        }
        System.out.println("operator is not supported");
        isFeasible = false;
        return 0;
    }

    /**
     * Aggregate, just like Projection, will not change any statistics
     * However, since it is necessary to read all tuples to calculate aggregates, cost will involve
     * reading all tuples
     *
     * @param node
     * @return number of tuples output, which is same as input, unless all attributes are aggregated
     */
    protected long getStatistics(Aggregate node) {
        long intuples = calculateCost(node.getBase());
        long tuplesize = node.getSchema().getTupleSize();
        long outcapacity = Math.max(1, Batch.getPageSize() / tuplesize);
        long pages = (long) Math.ceil(((double) intuples) / (double) outcapacity);
        cost += pages;

        // If all attributes are aggregated, then only write out 1 tuple
        if (node.isAllAggregate()) {
            return 1;
        }
        return intuples;
    }

    /**
     * Projection will not change any statistics
     * No cost involved as done on the fly
     * Same for Aggregate
     **/
    protected long getStatistics(Project node) {
        return calculateCost(node.getBase());
    }

    /**
     * Calculates the statistics and cost of join operation
     **/
    protected long getStatistics(Join node) {
        long lefttuples = calculateCost(node.getLeft());
        long righttuples = calculateCost(node.getRight());

        if (!isFeasible) {
            return 0;
        }

        Schema leftschema = node.getLeft().getSchema();
        Schema rightschema = node.getRight().getSchema();

        /** Get size of the tuple in output & correspondingly calculate
         ** buffer capacity, i.e., number of tuples per page **/
        long tuplesize = node.getSchema().getTupleSize();
        long outcapacity = Math.max(1, Batch.getPageSize() / tuplesize);
        long leftuplesize = leftschema.getTupleSize();
        long leftcapacity = Math.max(1, Batch.getPageSize() / leftuplesize);
        long righttuplesize = rightschema.getTupleSize();
        long rightcapacity = Math.max(1, Batch.getPageSize() / righttuplesize);
        long leftpages = (long) Math.ceil(((double) lefttuples) / (double) leftcapacity);
        long rightpages = (long) Math.ceil(((double) righttuples) / (double) rightcapacity);
        double tuples = (double) lefttuples * righttuples;

        // For every condition of the join, get the number of distinct values of attributes being joined
        // Divide tuples by the maximum distinct values of left and right join attributes
        for (Condition con : node.getConditionList()) {
            Attribute leftjoinAttr = con.getLhs();
            Attribute rightjoinAttr = (Attribute) con.getRhs();
            int leftattrind = leftschema.indexOf(leftjoinAttr);
            int rightattrind = rightschema.indexOf(rightjoinAttr);
            leftjoinAttr = leftschema.getAttribute(leftattrind);
            rightjoinAttr = rightschema.getAttribute(rightattrind);

            // Number of distinct values of left and right join attribute
            long leftattrdistn = ht.get(leftjoinAttr);
            long rightattrdistn = ht.get(rightjoinAttr);
            double maxdistinct = (double) Math.max(leftattrdistn, rightattrdistn);
            double mindistinct = (double) Math.min(leftattrdistn, rightattrdistn);

            // Main assumptions:
            // 1. Uniform distribution of tuple[attribute] across attribute's distinct values
            // 2. Containment of value sets
            // 3. Preservation of value sets (num distinct value of other attributes not changed)

            // default using the same as EQUAL for now due to difficulty in estimation of other conditionals
            switch (con.getExprType()) {
                case Condition.NOTEQUAL:
                    tuples = (double) Math.ceil(tuples - (tuples / maxdistinct));
                case Condition.EQUAL:
                    tuples = (double) Math.ceil(tuples / maxdistinct);
                default:
                    tuples = (double) Math.ceil(tuples / maxdistinct);
            }
            ht.put(leftjoinAttr, (long) mindistinct);
            ht.put(rightjoinAttr, (long) mindistinct);
        }
        long outtuples = (long) Math.ceil(tuples);

        /** Calculate the cost of the operation **/
        int joinType = node.getJoinType();
        long numbuff = BufferManager.getBuffersPerJoin();
        long joincost;

        switch (joinType) {
            case JoinType.BLOCKNESTED:
                joincost = leftpages + (long) Math.ceil(leftpages / numbuff) * rightpages;
                break;
            case JoinType.NESTEDJOIN:
                joincost = leftpages * rightpages;
                break;
            case JoinType.SORTMERGE:
                long B = node.getNumBuff();
                joincost = externalSortCost(leftuplesize, lefttuples, B) +
                        externalSortCost(righttuplesize, righttuples, B);
                break;
            default:
                System.out.println("join type is not supported");
                return 0;
        }
        cost = cost + joincost;

        return outtuples;
    }

    /**
     * Find number of incoming tuples, Using the selectivity find # of output tuples
     * * And statistics about the attributes
     * * Selection is performed on the fly, so no cost involved
     **/
    protected long getStatistics(Select node) {
        long intuples = calculateCost(node.getBase());
        if (!isFeasible) {
            System.out.println("notFeasible");
            return Long.MAX_VALUE;
        }

        Condition con = node.getCondition();
        Schema schema = node.getSchema();
        Attribute attr = con.getLhs();
        int index = schema.indexOf(attr);
        Attribute fullattr = schema.getAttribute(index);
        int exprtype = con.getExprType();

        /** Get number of distinct values of selection attributes **/
        long numdistinct = intuples;
        Long temp = ht.get(fullattr);
        numdistinct = temp.longValue();

        long outtuples;
        /** Calculate the number of tuples in result **/
        if (exprtype == Condition.EQUAL) {
            outtuples = (long) Math.ceil((double) intuples / (double) numdistinct);
        } else if (exprtype == Condition.NOTEQUAL) {
            outtuples = (long) Math.ceil(intuples - ((double) intuples / (double) numdistinct));
        } else {
            outtuples = (long) Math.ceil(0.5 * intuples);
        }

        /** Modify the number of distinct values of each attribute
         ** Assuming the values are distributed uniformly along entire
         ** relation
         **/
        for (int i = 0; i < schema.getNumCols(); ++i) {
            Attribute attri = schema.getAttribute(i);
            long oldvalue = ht.get(attri);
            long newvalue = (long) Math.ceil(((double) outtuples / (double) intuples) * oldvalue);
            ht.put(attri, outtuples);
        }
        return outtuples;
    }

    /**
     * The statistics file <tablename>.stat to find the statistics
     * * about that table;
     * * This table contains number of tuples in the table
     * * number of distinct values of each attribute
     **/
    protected long getStatistics(Scan node) {
        String tablename = node.getTabName();
        String filename = tablename + ".stat";
        Schema schema = node.getSchema();
        int numAttr = schema.getNumCols();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(filename));
        } catch (IOException io) {
            System.out.println("Error in opening file" + filename);
            System.exit(1);
        }
        String line = null;

        // First line = number of tuples
        try {
            line = in.readLine();
        } catch (IOException io) {
            System.out.println("Error in readin first line of " + filename);
            System.exit(1);
        }
        StringTokenizer tokenizer = new StringTokenizer(line);
        if (tokenizer.countTokens() != 1) {
            System.out.println("incorrect format of statistics file " + filename);
            System.exit(1);
        }
        String temp = tokenizer.nextToken();
        long numtuples = Long.parseLong(temp);
        try {
            line = in.readLine();
        } catch (IOException io) {
            System.out.println("error in reading second line of " + filename);
            System.exit(1);
        }
        tokenizer = new StringTokenizer(line);
        if (tokenizer.countTokens() != numAttr) {
            System.out.println("incorrect format of statastics file " + filename);
            System.exit(1);
        }
        for (int i = 0; i < numAttr; ++i) {
            Attribute attr = schema.getAttribute(i);
            temp = tokenizer.nextToken();
            Long distinctValues = Long.valueOf(temp);
            ht.put(attr, distinctValues);
        }

        /** Number of tuples per page**/
        long tuplesize = schema.getTupleSize();
        long pagesize = Math.max(Batch.getPageSize() / tuplesize, 1);
        long numpages = (long) Math.ceil((double) numtuples / (double) pagesize);

        cost = cost + numpages;

        try {
            in.close();
        } catch (IOException io) {
            System.out.println("error in closing the file " + filename);
            System.exit(1);
        }
        return numtuples;
    }


    /**
     * Calculate the cost of performing external sort
     * @param tuplesize Size of tuples
     * @param outtuples Number of output tuples
     * @param B Number of buffer pages
     * @return long, representing IO cost of performing sort
     */
    private long externalSortCost(long tuplesize, long outtuples, long B) {
        long pagesize = Math.max(Batch.getPageSize() / tuplesize, 1);
        long N = (long) Math.ceil((double) outtuples / (double) pagesize);
        return (long) (2 * N * (1 + Math.ceil(Math.log( Math.ceil(N /(double) B)) / Math.log(B - 1))));
    }

    /**
     * Calculate number of tuples produced by this operation, as well as Batch I/Os incurred.
     * @param node the Distinct operator
     * @return number of tuples produced by this operation
     */
    protected long getStatistics(Distinct node) {
        long intuples = calculateCost(node.getBase());
        if (!isFeasible) {
            System.out.println("notFeasible");
            return Long.MAX_VALUE;
        }

        // if your schema only has 1 column, calculate how many distinct tuples you will return,
        // otherwise estimate that you will return all of your incoming tuples, because of the independence
        // assumption
        Schema schema = node.getSchema();
        int schema_size = schema.getAttList().size();
        long outtuples = intuples;
        if (schema_size == 1) {
            // get the number of distinct values for that attribute
            outtuples = ht.get(schema.getAttribute(0));
        }

        /* calculate I/O cost which will be equal to 2N * (1 + 2N*log(N/B)/log(B-1)) */
        long tuplesize = schema.getTupleSize();
        long pagesize = Math.max(Batch.getPageSize() / tuplesize, 1);
        long B = node.getBuffer_size();
        cost += externalSortCost(tuplesize, outtuples, B);
        return outtuples;
    }

    /**
     * Calculate number of tuples produced by this operation, as well as Batch I/Os incurred.
     * @param node the Distinct operator
     * @return number of tuples produced by this operation
     */
    protected long getStatistics(OrderBy node) {
        long outtuples = calculateCost(node.getBase());
        if (!isFeasible) {
            System.out.println("notFeasible");
            return Long.MAX_VALUE;
        }

        // order by does not change your number of distinct values because you are just
        // reordering tuples. It also does not change the number of tuples you output.
        /* calculate I/O cost which will be equal to 2N * (1 + 2N*log(N/B)/log(B-1)) */
        long tuplesize = node.getSchema().getTupleSize();
        long pagesize = Math.max(Batch.getPageSize() / tuplesize, 1);
        long B = node.getBuffer_size();
        cost += externalSortCost(tuplesize, outtuples, B);
        return outtuples;
    }
}