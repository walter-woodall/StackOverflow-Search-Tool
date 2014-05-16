package cmsc433.p3;

import java.io.IOException;
import java.util.StringTokenizer;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.IntWritable.Comparator;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import cmsc433.p3.CodeCompareMR.HashReducer;
import cmsc433.p3.CodeCompareMR.TokenizerMapper;


/**
 * This class uses Hadoop to take an input list in the form of
 * "<code>string</code>&#09;<code>int</code>" and output a list where the keys
 * and values are the same, except the output should now be sorted by the
 * natural ordering of the values, the integers, and not the keys.
 */
public class ValueSortMR {

	/** Minimum <code>int</code> value for a pair to be included in the output.
	 * Pairs with an <code>int</code> less than this value are omitted. */
	public static int CUTOFF = 1;


	public static class SwapMapper extends
	Mapper<Object, Text, IntWritable, Text> {

		private IntWritable relevance = new IntWritable();
		private Text title = new Text();
		private int num;

		@Override
		public void map(Object key, Text value, Context context)
				throws IOException, InterruptedException {
			// Read lines using only tabs as the delimiter, as titles can contain spaces
			StringTokenizer itr = new StringTokenizer(value.toString(), "\t");
			// TODO: IMPLEMENT CODE HERE
			while(itr.hasMoreTokens()){
				title.set(itr.nextToken());
				num = Integer.parseInt(itr.nextToken());
				if(num >= CUTOFF){
					relevance.set(num);
					context.write(relevance, title);
				}

			}
		}
	}

	public static class SwapReducer extends
	Reducer<IntWritable, Text, Text, IntWritable> {

		@Override
		public void reduce(IntWritable key, Iterable<Text> values,
				Context context) throws IOException, InterruptedException {
			// TODO: IMPLEMENT CODE HERE
			for(Text title : values){
				context.write(title, key);
			}
		}
	}

	public static class DecreasingComparator extends Comparator {

		@SuppressWarnings("rawtypes")
		public int compare(WritableComparable a, WritableComparable b) {
			return -super.compare(a, b);
		}
		public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
			return -super.compare(b1, s1, l1, b2, s2, l2);
		}
	}

	/**
	 * This method performs value-based sorting on the given input by
	 * configuring the job as appropriate and using Hadoop.
	 * @param job Job created for this function
	 * @param input String representing location of input directory
	 * @param output String representing location of output directory
	 * @return True if successful, false otherwise
	 * @throws Exception
	 */
	public static boolean sort(Job job, String input, String output) throws Exception {
		job.setJarByClass(ValueSortMR.class);

		// TODO: IMPLEMENT CODE HERE
		job.setMapperClass(SwapMapper.class);
		job.setReducerClass(SwapReducer.class);

		job.setInputFormatClass(TextInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		job.setMapOutputKeyClass(IntWritable.class);
		job.setMapOutputValueClass(Text.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(IntWritable.class);

		job.setSortComparatorClass(DecreasingComparator.class);

		FileInputFormat.addInputPath(job, new Path(input));
		FileOutputFormat.setOutputPath(job, new Path(output));

		return job.waitForCompletion(true);
	}
}
