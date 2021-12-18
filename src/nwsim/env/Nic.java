package nwsim.env;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.LinkedBlockingQueue;

import nwsim.Param;
import nwsim.logger.NWLog;
import nwsim.network.ForwardHistory;
import nwsim.network.NAPTEntry;
import nwsim.network.Packet;
import nwsim.network.RouteInfo;
import nwsim.network.filtering.FilterRule;
import nwsim.network.routing.Rip;

/**
 *  Created by Hidehiro Kanemitsu
 */
public class Nic implements Runnable{

    /**
     * 02: インタフェース名
     */
    protected String nicName;

    /**
     * 帯域幅(Mbps) Full-Duplexを想定している．
     */
    protected double bw;

    /**
     * MACアドレス
     */
    protected String macAddress;

    /**
     * IPアドレス
     */
    protected String ipAddress;

    /**
     * デフォルトゲートウェイのIP
     */
    protected String gwIP;

    /**
     * ネットワークアドレス
     */
    protected String nwAddress;


    /**
     * ホスト部のビット数
     */
    protected int hostBit;

    /**
     * サブネットマスク
     */
    protected String subNetMask;

    /**
     * 可用帯域幅(Mbps) bw - 使用中の帯域幅の値
     */
    protected double availableBw;

    /**
     * 送信バッファ(FIFO)
     */
    protected LinkedBlockingQueue<Packet> sendBuffer;

    /**
     * 受信バッファ(FIFO)
     */
    protected LinkedBlockingQueue<Packet> receiveBuffer;

    /**
     * 受信バッファのサイズ 受信バッファの今のサイズがこの値であれば，これ以上，受け取れないようにする． 8kB - 8MB
     */
    protected long receiveBufferSize;

    /**
     * LANかどうか
     */
    protected boolean isLAN;

    /**
     * 自身が装着されているルータ
     */
    protected Node myNode;

    /**
     * ホップ毎の遅延(ms)
     */
    protected long delay;




    /**
     * コンストラクタ
     * 
     * @param bw
     * @param macAddress
     * @param ipAddress
     * @param size 受信バッファサイズ
     */
    public Nic(double bw, String macAddress, String ipAddress, long size) {
        this.nicName = null;
        //修正
        this.bw = Param.bw_list[(int)bw];

        this.macAddress = macAddress;
        this.ipAddress = ipAddress;
        this.availableBw = this.bw;
        this.sendBuffer = new LinkedBlockingQueue<Packet>();
        this.receiveBuffer = new LinkedBlockingQueue<Packet>();
        this.receiveBufferSize = size;
        this.hostBit = -1;
        this.nwAddress = null;
        this.isLAN = false;
        this.subNetMask = null;
        Env.getIns().getNicMap().put(this.macAddress, this);
        this.delay = Param.genLong(Param.delay_per_hop_min, Param.delay_per_hop_max, 1, Param.delay_per_hop_mu);



    }

    public long getDelay() {
        return delay;
    }

    public void setDelay(long delay) {
        this.delay = delay;
    }

    /**
     *
     * @param nic
     * @param bw
     * @param macAddress
     * @param ipAddress
     * @param gwIP
     * @param nw
     * @param hostBit
     * @param receiveBufferSize
     */
    public Nic(String nic, double bw, String macAddress, String ipAddress, String gwIP, String nw, int hostBit, long receiveBufferSize) {
        this.nicName = nic;
        this.bw = Param.bw_list[(int)bw];
        this.macAddress = macAddress;
        this.ipAddress = ipAddress;
        this.gwIP = gwIP;
        this.hostBit = hostBit;
        this.subNetMask = Param.getSubNetMask(this.hostBit);

        this.receiveBufferSize = receiveBufferSize;
        this.sendBuffer = new LinkedBlockingQueue<Packet>();
        this.receiveBuffer = new LinkedBlockingQueue<Packet>();
        this.nwAddress = nw;

        this.isLAN = false;
        this.subNetMask = null;
        Env.getIns().getNicMap().put(this.macAddress, this);


    }

    /**
     * パケットをNextHopのtoNicへ送る処理です．
     *
     * @param p パケット(既にtoMacアドレスは設定されていることが前提）
     * @param toNic nextHopのNIC
     * @return
     */
    public boolean sendPacketProcess(Packet p, Nic toNic){

        toNic.receiveProcess(p, this);
        if(p.getMinBW() >= toNic.getBw()){
            p.setMinBW((long)toNic.getBw());
        }
        if(toNic.getMyNode().getType() == Param.TYPE_ROUTER){
            Router r = (Router)toNic.getMyNode();
            r.incrementCurrentQueue();
        }
        return true;
    }

    /**
     * 一つ前のノードのfromNicから，当該Nicにおいてパケットが到着したときの
     * 処理です．ホップ遅延や転送履歴を記録し，そして当該Nicの受信バッファへ
     * パケットを追加します．
     * @param p
     * @param fromNic
     */
    private void receiveProcess(Packet p, Nic fromNic){


        long size = p.getTotalDataSize();
        Statistics stat = Env.getIns().findStat(p.getTranID());
        long currentBufferSize = this.getReceiveBuffer().size();
        if(p.isRequest() && stat != null){
            long totalBufferSize = stat.getTotalReqReceiveBufferSize();
            stat.setTotalReqReceiveBufferSize(currentBufferSize + totalBufferSize);
            if(this.getMyNode().getType() == Param.TYPE_ROUTER){
                Router r = (Router)this.getMyNode();
                long totalQueue = stat.getTotalReqQueueSize();
                stat.setTotalReqQueueSize(totalQueue + r.getCurrentQueueLength());
                if(stat.getMaxReqQueueSize() <= r.getCurrentQueueLength()){
                    stat.setMaxReqQueueSize(r.getCurrentQueueLength());
                }

            }

            if(stat.getMaxReqReceiveBufferSize() <= currentBufferSize){
               stat.setMaxReqReceiveBufferSize(currentBufferSize);
           }
        }else{
            if((this.getMyNode().getType() == Param.TYPE_ROUTER)&& stat != null){
                Router r = (Router)this.getMyNode();
                if(stat != null){
                    long totalQueue = stat.getTotalResQueueSize();
                    stat.setTotalResQueueSize(totalQueue + r.getCurrentQueueLength());
                    if(stat.getMaxResQueueSize() <= r.getCurrentQueueLength()){
                        stat.setMaxResQueueSize(r.getCurrentQueueLength());
                    }
                }


            }
            if(stat != null){
                long totalBufferSize = stat.getTotalResReceiveBufferSize();

                stat.setTotalResReceiveBufferSize(currentBufferSize + totalBufferSize);

                if(stat.getMaxResReceiveBufferSize() <= currentBufferSize){
                    stat.setMaxResReceiveBufferSize(currentBufferSize);
                }
            }

        }

        if(p.getFlag() == Param.PACKET_END){
            double comTime = Param.getIns().calcComTime(p, fromNic, this);
            int len = 0;
            if(p.isRequest()){
                len = p.getRequestHistoryList().size();

            }else{
                len = p.getResponseHistoryList().size();
            }
            long endTime = this.delay  + (long)comTime;
            p.addTotalDelay(endTime);
            try{
                //Thread.sleep(totalTime);
            }catch(Exception e){
                e.printStackTrace();
            }
        }else{
            p.addTotalDelay(this.delay);
        }
        this.getReceiveBuffer().add(p);


    }

    @Override
    public void run() {
        while(true){
            try{
                Thread.sleep(0);

                //パケット受信
                if(!this.receiveBuffer.isEmpty()){
                    //最初の要素を取ってくる．
                    Packet p = this.receiveBuffer.poll();

                    try{
                       // Thread.sleep(this.delay);
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                    if(p.isRequest()){
                        ForwardHistory h = p.getRequestHistoryList().getLast();
                        h.setArrivalTime(System.currentTimeMillis());
                    }else{
                        ForwardHistory h = p.getResponseHistoryList().getLast();
                        h.setArrivalTime(System.currentTimeMillis());
                    }
                    //到着時刻をセットする．
                    //パケットのタイプを見る．
                    if(p.getType() == Param.MSG_TYPE.ARP){
                        if(this.getIpAddress().equals(p.getToIP())){
                            //GWならば，子たちへ転送する．
                            //このとき，p->toIPは，GWIPがセットされているので．
                            //この時点で当該NICがGWのNICであることが保証されている．
                            this.broadCastARPReq(p);
                        }else{
                            String ip = p.getData();
                            if(this.getIpAddress().equals(ip)){
                                this.myNode.returnPacketProcess(p,this, Param.MSG_TYPE.ARP);
                            }else{
                                //何もしない．
                            }
                        }
                        continue;
                    }
                    if(p.getType() == Param.MSG_TYPE.RIP_METRIC){
                        if(this.getMyNode().getType() == Param.TYPE_ROUTER){
                            Router r = (Router)this.getMyNode();
                            Rip rip = (Rip)r.getUsedRouting();
                            //メトリック反映処理
                            rip.processMetric(p, this);
                        }
                        continue;
                    }
                    //もし制御パケットであれば，
                    if(p.getType() == Param.MSG_TYPE.CTRL){
                        if(p.isRequest()){
                            //要求パケットの場合
                            //何らかの処理をして，送り返す．
                            if(p.getToIP().equals(this.getIpAddress())){
                                //最終到達地
                                //経路制御に関するMapを返す．
                                HashMap<String, HashMap<String, RouteInfo>> routeMap = this.myNode.getUsedRouting().getUpdatedRouteMap();
                                p.setAplMap(routeMap);
                                this.getMyNode().returnPacketProcess(p, this, p.getType());

                            }else{
                                //そうでない場合（普通はありえない）
                                //System.out.println();
                            }
                        }else{
                            //応答パケットの場合の処理
                            this.getMyNode().getUsedRouting().updateRouteMap(p);
                            //System.out.println("******おうとうきたぜ");
                        }


                        continue;
                    }
                    if(this.getMyNode().getType() == Param.TYPE_ROUTER){
                        Router r = (Router)this.getMyNode();
                        if(r.getCurrentQueueLength() > r.getMaxQueueLength()){
                            HashMap<String, Double> map = Param.getIns().calcAvgBufferSize(p);
                           NWLog.getIns().tranLog(p, "QueueMaxError");
                            continue;

                        }
                    }
                    //要求送信元のIDを取得
                    String srcID = p.getRequestHistoryList().getFirst().getFromID();
                    //宛先 == 自分かどうか
                    if(p.getToIP().equals(this.getIpAddress())){
                        if(p.isRequest()){
                            //ここが最終地点なので，記録しておく．
//DNATモードONの場合 START
//ルータからポート転送してLAN内のサーバへパケットを転送する機能
//動くには動くが，ログ出力が猛烈に遅いのでコメントアウト中．
//しかもONにしても，シミュレータ上では，あまり影響なし？
                            /*
                            //要求パケット到着処理．
                            if(this.getMyNode().getType() == Param.TYPE_ROUTER) {
                                Router r = (Router) this.getMyNode();
                                FilterRule rule = r.findDNATFilter(p.getToPort());
                                if(rule == null){
                                    //ここが最終地点なので，記録しておく．
                                    this.myNode.addReceivedPacket(p);
                                    if(p.getFlag()==Param.PACKET_END){
                                        //最終パケットなので，返信処理をする．
                                        this.myNode.returnPacketProcess(p,this, p.getType());
                                    }

                                }else{
                                    //DNATフィルタがあれば，フィルタ処理をする．
                                    r.DNATProcess(p, this);

                                }

                            }else{
                                //Computer側に要求パケットが来たとき．
                                //DNATの場合
                                //まずは受信履歴に登録
                                this.myNode.addReceivedPacket(p);
                                if(p.getFlag()==Param.PACKET_END){
                                    //最終パケットなので，返信処理をする．
                                    this.myNode.returnPacketProcess(p,this, p.getType());
                                }

                            }

 */
//DNATモードONの場合 START
//DNATモードOFFの場合 START
//つまりポート転送なしで，常にルータが，パケットの最終到達地である場合．
                            //後は，送り返す処理をする．
                            this.myNode.addReceivedPacket(p);
                            //特に，最終パケットである場合の処理
                            if(p.getFlag()==Param.PACKET_END){
                                //最後のパケットを受信すると，応答を行う準備をする．
                                this.myNode.returnPacketProcess(p,this, p.getType());
                            }
//Non-DNATモードOFFの場合 END
                        }else{
                            //応答の場合は，パケットの宛先がNextHopへと次々と変わるので，
                            //もともとの送信元を決定する基準が無い．
                            //if(this.getNwAddress().equals("192.168.0.0")){
                            //if(this.getMyNode().getType() == Param.TYPE_ROUTER){
                            if(!this.myNode.getID().equals(srcID)){
                                if(p.getResponseHistoryList().size()>=2){
                                    //System.out.println();
                                }
                                this.myNode.processResponse(p, this);
                            }else{
                                //要求送信元に応答パケットが到着した場合．
                            //if(this.getMyNode().getType() == Param.TYPE_COMPUTER){
                                HashMap<String, Double> map = Param.getIns().calcAvgBufferSize(p);
                                //とりあえず格納．
                                this.myNode.addReceivedPacket(p);
                                //特に，最終パケットである場合の処理
                                if(p.getFlag() == Param.PACKET_END){
                                    Statistics stat = Env.getIns().findStat(p.getTranID());
                                    stat.setResFinishTime(System.currentTimeMillis());
                                   // NWLog.getIns().tranLog(p, "Hit");
                                    Param.getIns().tranLog(p, "Hit");
                               }
                            }
                        }
                    }else{

                        //Nodeに対して処理を依頼する．
                        //TCPで，宛先が違う場合．転送する．
                        this.myNode.processPacket(p, this);
                    }

                }

                if(!this.sendBuffer.isEmpty()){

                }
            }catch(Exception e){
                e.printStackTrace();
            }
        }

    }

    /**
     * やってきたパケットpをもとにして，当該nicと
     * 同一NWに属する子ノードに対してARP要求を一斉送信する．
     * ちなみに当該NICは，GWルータのNICであることが保証されています．
     * @param p
     */
    public void broadCastARPReq(Packet p){

    }

    public Node getMyNode() {
        return myNode;
    }

    public void setMyNode(Node myNode) {
        this.myNode = myNode;
    }

    public String getSubNetMask() {
        return subNetMask;
    }

    public void setSubNetMask(String subNetMask) {
        this.subNetMask = subNetMask;
    }

    public boolean isLAN() {
        return isLAN;
    }

    public void setLAN(boolean LAN) {
        isLAN = LAN;
    }

    public String getNwAddress() {
        return nwAddress;
    }

    public void setNwAddress(String nwAddress) {
        this.nwAddress = nwAddress;
    }

    public String getNicName() {
        return nicName;
    }

    public void setNicName(String nicName) {
        this.nicName = nicName;
    }

    public double getBw() {
        return bw;
    }

    public void setBw(double bw) {
        this.bw = bw;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public double getAvailableBw() {
        return availableBw;
    }

    public void setAvailableBw(double availableBw) {
        this.availableBw = availableBw;
    }

    public LinkedBlockingQueue<Packet> getSendBuffer() {
        return sendBuffer;
    }

    public void setSendBuffer(LinkedBlockingQueue<Packet> sendBuffer) {
        this.sendBuffer = sendBuffer;
    }

    public LinkedBlockingQueue<Packet> getReceiveBuffer() {
        return receiveBuffer;
    }

    public void setReceiveBuffer(LinkedBlockingQueue<Packet> receiveBuffer) {
        this.receiveBuffer = receiveBuffer;
    }

    public long getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(long receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    public String getGwIP() {
        return gwIP;
    }

    public void setGwIP(String gwIP) {
        this.gwIP = gwIP;
    }

    public int getHostBit() {
        return hostBit;
    }

    /**
     *
     * @param hostBit
     */
    public void setHostBit(int hostBit) {
        this.hostBit = hostBit;
        this.subNetMask = Param.getSubNetMask(hostBit);

    }


}
