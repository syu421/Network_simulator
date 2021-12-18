package nwsim.network.routing;

import nwsim.Param;
import nwsim.env.Env;
import nwsim.env.Nic;
import nwsim.env.Router;
import nwsim.network.Packet;
import nwsim.network.RouteInfo;
import nwsim.network.ForwardHistory;
import java.util.*;


public class Rip extends AbstractRouting {
    public Rip(long exchangeSpan, Router router){
        super(exchangeSpan, router);
    }

    @Override
    public double calcMetric(RouteInfo info){
        return 0;
    }

    @Override
    public HashMap<String, HashMap<String, RouteInfo>> getUpdatedRouteMap(){
        return super.getUpdatedRouteMap();
    }

    @Override
    public boolean updateRouteMap(Packet p){
        return super.updateRouteMap(p);
    }

    public void processMetric(Packet p, Nic atNic){
        if(p.getToIP().equals(atNic.getIpAddress())){
            Iterator<ForwardHistory> fIte = p.getRequestHistoryList().iterator();
            boolean isForwarded = false;
            while(fIte.hasNext()){
                ForwardHistory history = fIte.next();
                if(history.getFromID().equals(p.getFromIP())){
                    isForwarded = true;
                    break;
                }
            }
            if (isForwarded){
                return;
            }
            HashMap<String, String> metriMap = (HashMap<String, String>) p.getAplMap();
            String nwAddress = metriMap.get("nwaddress");
            //もし,ルーティングテーブルにパケットにあるNWアドレスがあれば,
            if(this.router.getRoutingTable().containsKey(nwAddress)){
                HashMap<String, RouteInfo> rMap = this.router.getRoutingTable().get(nwAddress);
                if(rMap.containsKey(p.getFromIP())){
                    RouteInfo info = rMap.get(p.getFromIP());
                    int val = Integer.valueOf(metriMap.get("metric")).intValue();
                    val++;
                    info.setMetric(val);
                    //タイムスタンプ更新
                    info.setLastUpdatedTime(System.currentTimeMillis());
                    //メトリックも反映させる
                    metriMap.put("metric", String.valueOf(val));

                    //各NextHopに対して,パケットを送信する
                    Iterator<HashMap<String, RouteInfo>> rIte = this.router.getRoutingTable().values().iterator();
                    while(rIte.hasNext()){
                        HashMap<String, RouteInfo> routeMap = rIte.next();
                        Iterator<String> nextIte = routeMap.keySet().iterator();
                        while(nextIte.hasNext()){
                            String nextHop = nextIte.next();
                            if(nextHop.equals(atNic.getIpAddress())){
                                continue;
                            }
                            if(nextHop.startsWith("192.168.0")){
                                continue;
                            }
                            RouteInfo fInfo = routeMap.get(nextHop);
                            Nic fromNic = this.router.getNicMap().get(fInfo.getNicName());
                            HashMap<String, String> map = this.router.getArpMap().get(fInfo.getNicName());
                            String nextHopMac = map.get(nextHop);
                            Nic nextHopNic = Env.getIns().getNicMap().get(nextHopMac);
                            //nextHop向けにパケット生成
                            Packet p_metric = this.router.genCtrlPacket(fromNic, nextHopNic, 1111, Param.MSG_TYPE.RIP_METRIC);
                            p.setRequest(true);
                            HashMap<String, String> ripMap = new HashMap<String, String>();
                            ripMap.put("metric", String.valueOf(val));
                            ripMap.put("nwaddress", nwAddress);
                            p_metric.setAplMap(ripMap);
                            this.router.sendPacket(p_metric, fromNic);
                        }
                    }
                }
            }
        }
    }
}